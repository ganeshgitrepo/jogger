package org.jogger;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.jogger.http.Request;
import org.jogger.http.Response;
import org.jogger.http.servlet.ServletRequest;
import org.jogger.http.servlet.ServletResponse;
import org.jogger.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A server that handles HTTP requests using the provided {@link Jogger} instance.
 * 
 * @author German Escobar
 */
public class JoggerServer {
	
	private Logger log = LoggerFactory.getLogger(JoggerServer.class);
	
	private static final int DEFAULT_PORT = 5000;
	
	/**
	 * Used to create the {@link Jogger} instance.
	 */
	private JoggerFactory joggerFactory;
	
	/**
	 * The cached version of the app configuration.
	 */
	private Jogger jogger;

	/**
	 * The Jetty server instance.
	 */
	private Server server;
	
	/**
	 * The port in which the server will respond.
	 */
	private int port = DEFAULT_PORT;
	
	/**
	 * Constructor.
	 * 
	 * @param jogger the application configuration that this server will use to handle the HTTP requests.
	 */
	public JoggerServer(final Jogger jogger) {
		Preconditions.notNull(jogger, "no jogger provided.");

		this.joggerFactory = new JoggerFactory() {
			@Override
			public Jogger configure() {
				return jogger;
			}
		};
		this.jogger = jogger;
	}
	
	/**
	 * Constructor. 
	 * 
	 * @param joggerFactory the factory from which we will retrieve the {@link Jogger} instance.
	 */
	public JoggerServer(final JoggerFactory joggerFactory) {
		Preconditions.notNull(joggerFactory, "no joggerFactory provided");
		
		this.joggerFactory = joggerFactory;
		try {
			this.jogger = joggerFactory.configure();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		Preconditions.notNull(jogger, "joggerFactory is not providing a jogger instance.");
		
	}
	
	public Jogger getJogger() {
		return jogger;
	}
	
	/**
	 * Starts the HTTP server listening in the configured <code>port</code> attribute.
	 * 
	 * @return itself for method chaining.
	 */
	public JoggerServer listen() {
		return listen(port);
	}

	/**
	 * Starts the HTTP server listening in the specified <code>port</code>
	 * 
	 * @param port the port in which the HTTP server will listen.
	 * 
	 * @return itself for method chaining.
	 */
	public JoggerServer listen(int port) {
		this.port = port;

		// configure the Jetty server
		server = new Server(port);
		server.setHandler(new JoggerHandler(jogger));

		// start the Jetty server
		try {
			server.start();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		return this;
	}

	/**
	 * Joins to the server thread preventing the program to finish.
	 * 
	 * @return itself for method chaining.
	 * @throws InterruptedException if the thread is interrupted.
	 */
	public JoggerServer join() throws InterruptedException {
		server.join();
		return this;
	}

	/**
	 * Stops the HTTP server.
	 * 
	 * @return itself for method chaining.
	 */
	public JoggerServer stop() {
		try {
			server.stop();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		return this;
	}
	
	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	/**
	 * The Jetty handler that will handle HTTP requests.
	 * 
	 * @author German Escobar
	 */
	private class JoggerHandler extends AbstractHandler {
		
		private RouteRequestExecutor routeExecutor;
		
		private AssetRequestExecutor assetExecutor;
		
		private ExceptionHandler defaultExceptionHandler;
		
		private NotFoundHandler defaultNotFoundHandler;
		
		public JoggerHandler(Jogger jogger) {
			routeExecutor = new RouteRequestExecutor(jogger);
			assetExecutor = new AssetRequestExecutor(jogger);
			defaultExceptionHandler = new DefaultExceptionHandler();
			defaultNotFoundHandler = new DefaultNotFoundHandler();
		}

		@Override
		public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest servletRequest,
				HttpServletResponse servletResponse) throws IOException, ServletException {
			
			if (Environment.isDevelopment()) {
				reloadJogger();
			}

			// try to find a matching route
			Route route = jogger.getRoute( servletRequest.getMethod(), getPath(servletRequest) );
			
			// build the Jogger request/response objects
			ServletRequest request = new ServletRequest(route, servletRequest);
			ServletResponse response = new ServletResponse(servletResponse, jogger.getTemplateEngine());
			try {
				request.init();
			} catch (Exception e) {
				handleException(e, request, response);
				return;
			}
			
			try {
				if (route != null) {
					routeExecutor.execute(request, response);
				} else if (jogger.getAssetLoader() != null) {
					assetExecutor.execute(request, response);
				} else {
					response.notFound();
				}
				
				if (response.getStatus() == Response.NOT_FOUND) {
					handleNotFound(request, response);
				}
				
			} catch (Exception e) {
				response.status(Response.INTERNAL_ERROR);
				handleException(e, request, response);
			} finally {
				baseRequest.setHandled(true);
			}
		}
		
		private void reloadJogger() throws ServletException {
			try {
				jogger = joggerFactory.configure();
			} catch (Exception e) {
				throw new ServletException(e);
			}
		}
		
		private String getPath(HttpServletRequest request) {
			String path = request.getRequestURI();
			
			if (path == null) {
				return "/";
			}
			
			return path;
		}
		
		/**
		 * Helper method. Called when the requested asset was not found.
		 * 
		 * @param request the Jogger HTTP request object.
		 * @param response the Jogger HTTP response object.
		 */
		private void handleNotFound(Request request, Response response) {
			
			NotFoundHandler notFoundHandler = jogger.getNotFoundHandler();
			if (notFoundHandler == null) {
				defaultNotFoundHandler.handle(request, response);
				return;
			}
			
			try {
				notFoundHandler.handle(request, response);
			} catch (Exception e) {
				log.error("Exception running the not found handler ... using the default one: " + e.getMessage(), e);
				defaultNotFoundHandler.handle(request, response);
			}
			
		}
		
		private void handleException(Exception e, Request request, Response response) {
			
			log.error("Exception processing request (" + request.getMethod() + " " + request.getPath() + "): " + e.getMessage(), e);

			ExceptionHandler exceptionHandler = jogger.getExceptionHandler();
			if (exceptionHandler == null) {
				defaultExceptionHandler.handle(e, request, response);
				return;
			}

			try {
				exceptionHandler.handle(e, request, response);
			} catch (Exception f) {
				log.error("Exception running the exception handler ... using the default one: " + e.getMessage(), e);
				defaultExceptionHandler.handle(e, request, response);
			}

		}
			
	}
}
