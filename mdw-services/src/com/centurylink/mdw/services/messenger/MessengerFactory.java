/*
 * Copyright (C) 2017 CenturyLink, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.centurylink.mdw.services.messenger;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.config.PropertyManager;
import com.centurylink.mdw.constant.PropertyNames;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;

public class MessengerFactory {

    private static final String JMS = "jms";
    private static final String HTTP = "http";
    private static final String SAME_SERVER = "same_server";

    private static String internalMessenger;
    private static String serviceContext;

    public static void init(String restServiceContext) {
        serviceContext = restServiceContext;
        StandardLogger logger = LoggerUtil.getStandardLogger();
        String v = PropertyManager.getProperty(PropertyNames.MDW_CONTAINER_MESSENGER);
        if (JMS.equalsIgnoreCase(v)) internalMessenger = JMS;
        else if (HTTP.equalsIgnoreCase(v)) internalMessenger = HTTP;
        else if (SAME_SERVER.equalsIgnoreCase(v)) internalMessenger = SAME_SERVER;
        else internalMessenger = (ApplicationContext.getJmsProvider() == null) ? HTTP : JMS;
        logger.info("Internal Messenger: " + internalMessenger);
    }

    public static InternalMessenger newInternalMessenger() {
        if (internalMessenger.equals(HTTP)) return new InternalMessengerRest();
        else if (internalMessenger.equals(JMS)) return new InternalMessengerJms();
        else return new InternalMessengerSameServer();
    }

    /**
     * Server specification can be one of the following forms
     *   a) URL of the form t3://host:port, iiop://host:port, rmi://host:port, http://host:port/context_and_path
     *   b) a logical server name, in which case the URL is obtained from property mdw.remote.server.<server-name>
     *   c) server_name@URL
     *   d) null, in which case it indicates on the same site (same domain)
     * @return the messenger for the corresponding server
     */
    public static IntraMDWMessenger newIntraMDWMessenger() {
            if (internalMessenger.equals(JMS))
                return new IntraMDWMessengerJms(null);
            else
                return new IntraMDWMessengerRest(null, serviceContext);
    }

    public static boolean internalMessageUsingJms() {
        return internalMessenger.equals(JMS);
    }

    /**
     * Returns URL for this engine. Used to pass it to remove servers:
     *   a) when invoking a remote process
     *   b) when sending message to remote detail task manager
     *   c) for central task manager to generate a unique session ID when linking to detail page
     * For WebLogic: t3://host:port (works for both JMS for RMI. iiop may work as well for RMI)
     * For Tomcat: rmi://host:port
     */
    public static String getEngineUrl() {
        return ApplicationContext.getMdwHubUrl() + "/services";
    }
}
