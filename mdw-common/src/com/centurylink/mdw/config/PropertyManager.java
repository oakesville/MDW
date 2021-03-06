package com.centurylink.mdw.config;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.model.JsonObject;
import com.centurylink.mdw.startup.StartupException;
import com.centurylink.mdw.util.file.FileHelper;
import org.apache.xmlbeans.XmlException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public abstract class PropertyManager {

    public static final String MDW_PROPERTIES_FILE_NAME = "mdw.properties";
    public static final String APPLICATION_PROPERTIES_FILE_NAME = "application.properties";
    public static final String MDW_CONFIG_LOCATION = "mdw.config.location";
    public static final String MDW_PROPERTY_MANAGER = "mdw.property.manager";

    private static PropertyManager instance = null;
    private Map<String,String> sources = new HashMap<>();
    protected Map<String,String> getSources() {
        return sources;
    }

    /**
     * Properties for group
     */
    public abstract Properties getProperties(String group) throws PropertyException;

    public abstract String getStringProperty(String name);
    public abstract void setStringProperty(String name, String value);

    public abstract Properties getAllProperties();
    public boolean isEncrypted(String propName) {
        return false;
    }

    public String get(String name) {
        return getStringProperty(name);
    }

    public String get(String name, String defaultValue) {
        String v = get(name);
        return v == null ? defaultValue : v;
    }

    public int get(String name, int defaultValue) {
        String v = get(name);
        if (v == null)
            return defaultValue;
        try {
            return Integer.parseInt(v);
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long get(String name, long defaultValue) {
        String v = get(name);
        if (v == null)
            return defaultValue;
        try {
            return Long.parseLong(v);
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean get(String name, boolean defaultValue) {
        String v = get(name);
        if (v == null)
            return defaultValue;
        return v.equalsIgnoreCase("true");
    }

    private static String configLocation;

    /**
     * Directory where MDW config files can be found.
     */
    public static String getConfigLocation() {
        if (configLocation == null) {
            String configLoc = System.getProperty(MDW_CONFIG_LOCATION);
            if (configLoc != null) {
                if (!configLoc.endsWith("/"))
                    configLoc = configLoc + "/";
                configLocation = configLoc;
                System.out.println("Loading configuration files from '" + new File(configLoc).getAbsolutePath() + "'");
            }
        }
        return configLocation;
    }

    /**
     * returns the handle to the property manager
     *
     * @return PropertyManager
     */
    public static PropertyManager getInstance() {
        if (instance == null) {
            try {
                initializePropertyManager();
            }
            catch (StartupException e) {
                // should not reach here, as the property manager should be
                // initialized by now
                throw new RuntimeException(e);
            }
            // container property manager will never hit this
        }
        return instance;
    }

    public static String getProperty(String name) {
        return getInstance().getStringProperty(name);
    }

    public static String getProperty(String name, String defaultValue) {
        String v = getInstance().getStringProperty(name);
        return v == null ? defaultValue : v;
    }

    public static int getIntegerProperty(String name, int defaultValue) {
        return getInstance().get(name, defaultValue);
    }

    public static long getLongProperty(String name, long defaultValue) {
        return getInstance().get(name, defaultValue);
    }

    public static boolean getBooleanProperty(String name, boolean defaultValue) {
        return getInstance().get(name, defaultValue);
    }

    public static List<String> getListProperty(String name) {
        return getInstance().getList(name);
    }

    public List<String> getList(String name) {
        String v = getStringProperty(name);
        if (v == null)
            return null;
        return Arrays.asList(v.trim().split("\\s*,\\s*"));
    }

    public static PropertyManager initializeMockPropertyManager() {
        if (instance == null) {
            instance = new MockPropertyManager();
        }
        return instance;
    }

    private synchronized static PropertyManager initializePropertyManager()
            throws StartupException {

        String pm = System.getProperty(MDW_PROPERTY_MANAGER);
        if (pm != null) {
            System.out.println("Using Property Manager: " + pm);
            try {
                Class<?> cls = PropertyManager.class.getClassLoader().loadClass(pm);
                instance = (PropertyManager) cls.newInstance();
            }
            catch (Exception e) {
                String msg = "Cannot create property manager " + pm;
                System.out.println(msg);
                e.printStackTrace();
                throw new StartupException(msg);
            }
        }
        else {
            File yamlFile = FileHelper.getConfigurationFile("mdw.yaml");
            if (yamlFile.exists()) {
                try {
                    instance = new YamlPropertyManager(yamlFile);
                }
                catch (IOException ex) {
                    throw new StartupException(ex.getMessage(), ex);
                }
            }
            else {
                instance = new JavaPropertyManager();
            }
        }
        // override limberest json formatting
        JsonObject.configure(instance);

        return instance;
    }

    final protected void loadFromStream(Properties properties, InputStream stream, String source)
            throws XmlException, IOException {
        try {
            Properties props = new Properties();
            props.load(stream);
            for (Object key : props.keySet()) {
                String pn = (String) key;
                String pv = props.getProperty(pn);
                if (pv.length() > 0) {
                    properties.put(pn, pv);
                    getSources().put(pn, source);
                }
                else {
                    properties.remove(pn);
                }
            }
        }
        finally {
            if (stream != null) {
                try {
                    stream.close();
                }
                catch (Exception e) {
                }
            }
        }
    }

    public static boolean isYaml() {
        return getInstance() instanceof YamlPropertyManager;
    }
}