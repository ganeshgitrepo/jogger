package org.jogger;

/**
 * Used by {@link Interceptor} objects to proceed with the execution of the request. 
 * 
 * @author German Escobar
 */
public interface InterceptorChain {

	/**
	 * Causes the next interceptor (or controller if there are no more interceptors) to be invoked.
	 * 
	 * @throws Exception
	 */
	void proceed() throws Exception;
	
}
