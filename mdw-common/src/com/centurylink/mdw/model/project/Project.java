package com.centurylink.mdw.model.project;

import com.centurylink.mdw.model.system.MdwVersion;
import com.centurylink.mdw.model.variable.VariableType;
import com.centurylink.mdw.model.workflow.ActivityImplementor;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

@SuppressWarnings("unused")
public interface Project {

    File getAssetRoot() throws IOException;

    String getHubRootUrl() throws IOException;

    MdwVersion getMdwVersion() throws IOException;

    Data getData();

    default String readData(String name) throws IOException {
        return null;
    }

    default List<String> readDataList(String name) throws IOException {
        return null;
    }

    default SortedMap<String,String> readDataMap(String name) throws IOException {
        return null;
    }

    /**
     * Map of implClass to implementor.
     */
    default Map<String,ActivityImplementor> getActivityImplementors() throws IOException {
        return null;
    }

    /**
     * Map of typeName to variable type.
     */
    default Map<String,VariableType> getVariableTypes() throws IOException {
        return null;
    }
}
