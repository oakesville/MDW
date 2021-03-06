package com.centurylink.mdw.container.plugin.tomcat;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.container.ContextProvider;
import com.centurylink.mdw.container.JmsProvider;
import com.centurylink.mdw.container.plugin.MdwTransactionManager;
import com.centurylink.mdw.container.plugin.activemq.ActiveMqJms;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.lang.reflect.Method;
import java.rmi.Remote;

@SuppressWarnings("unused")
public class TomcatContext implements ContextProvider {

    private boolean useMdwTransactionManager;

    public TomcatContext() {
        String txMgr = System.getProperty(TRANSACTION_MANAGER_SYSTEM_PROPERTY);
        if (txMgr == null || txMgr.equals(MdwTransactionManager.class.getName()))
            useMdwTransactionManager = true;
    }

    public String getTransactionManagerName() {
        return JAVA_TRANSACTION_MANAGER;
    }

    public int getServerPort() throws Exception {
        String portStr = System.getProperty("mdw.server.port");
        if (portStr == null)
            portStr = System.getProperty("server.port"); // spring boot config
        if (portStr == null && ApplicationContext.isSpringBoot()) {
            portStr = "8080"; // this is only the default (should have been determined above)
        }

        if (portStr != null )
            return Integer.parseInt(portStr);

        // use tomcat mbean mechanism
        MBeanServer mBeanServer = MBeanServerFactory.findMBeanServer(null).get(0);
        ObjectName name = new ObjectName("Catalina", "type", "Server");
        Object server = mBeanServer.getAttribute(name, "managedResource");

        Method findServices = server.getClass().getMethod("findServices", (Class<?>[])null);
        Object[] services = (Object[])findServices.invoke(server, (Object[])null);
        for (Object service : services) {
            Method findConnectors = service.getClass().getMethod("findConnectors", (Class<?>[])null);
            Object[] connectors = (Object[])findConnectors.invoke(service, (Object[])null);
            for (Object connector : connectors) {
                Method getProtocolHandler = connector.getClass().getMethod("getProtocolHandler", (Class<?>[])null);
                Object protocolHandler = getProtocolHandler.invoke(connector, (Object[])null);
                if (protocolHandler != null && protocolHandler.getClass().getSimpleName().startsWith("Http")) {
                    Method getPort = connector.getClass().getMethod("getPort", (Class<?>[])null);
                    Integer portObj = (Integer) getPort.invoke(connector, (Object[])null);
                    if (portObj.intValue() > 0)
                        return portObj.intValue(); // return first match
                }
            }
        }
        return 0;
    }

    public Object lookup(String hostPort, String name, Class<?> cls) throws NamingException {

        if (cls.getName().equals("javax.transaction.TransactionManager") && useMdwTransactionManager) {
            return MdwTransactionManager.getInstance();
        }
        else if (cls.getName().equals("javax.jms.Topic")) {
            JmsProvider jmsProvider = ApplicationContext.getJmsProvider();
            if (!(jmsProvider instanceof ActiveMqJms))
                throw new NamingException("Unsupported JMS Provider: " + jmsProvider);
            ActiveMqJms activeMqJms = (ActiveMqJms) jmsProvider;
            return activeMqJms.getTopic(name);
        }

        try {
            Context initCtx = new InitialContext();
            Context envCtx = (Context) initCtx.lookup("java:comp/env");
            return envCtx.lookup(name);
        }
        catch (Exception e) {
            NamingException ne = new NamingException("Failed to look up " + name);
            ne.initCause(e);
            throw ne;
        }
    }
}
