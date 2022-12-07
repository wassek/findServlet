package org.jahia.modules.findservlet.activator;

import javax.servlet.ServletException;

import org.jahia.modules.findservlet.servlet.FindServlet;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindServletActivator implements BundleActivator {

	private static Logger logger = LoggerFactory.getLogger(FindServletActivator.class);
	@Override
	public void start(BundleContext bundleContext) throws Exception {
        ServiceReference realServiceReference = bundleContext.getServiceReference(HttpService.class.getName());
        HttpService httpService = (HttpService) bundleContext.getService(realServiceReference);
        try {
            httpService.registerServlet("/find", new FindServlet(), null, null);
            logger.info("Successfully registered custom servlet at /modules/find");
        } catch (ServletException e) {
            e.printStackTrace();
        } catch (NamespaceException e) {
            e.printStackTrace();
        }

	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception {
        ServiceReference realServiceReference = bundleContext.getServiceReference(HttpService.class.getName());
        HttpService httpService = (HttpService) bundleContext.getService(realServiceReference);
        httpService.unregister("/find");
        logger.info("Successfully unregistered custom servlet at /modules/find");


	}

}
