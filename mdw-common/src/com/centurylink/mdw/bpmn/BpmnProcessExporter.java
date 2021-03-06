package com.centurylink.mdw.bpmn;

import com.centurylink.mdw.cli.Dependency;
import com.centurylink.mdw.export.ProcessExporter;
import com.centurylink.mdw.model.project.Data;
import com.centurylink.mdw.model.workflow.Process;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BpmnProcessExporter implements ProcessExporter {

    @Override
    public List<Dependency> getDependencies() {
        List<Dependency> dependencies = new ArrayList<>();
        dependencies.add(new Dependency(Data.GIT_BASE_URL + "/mdw/blob/master/mdw/libs/bpmn-schemas.jar?raw=true", "./bpmn-schemas.jar", 2011745L));
        dependencies.add(new Dependency("org/apache/xmlbeans/xmlbeans/2.4.0/xmlbeans-2.4.0.jar", 2694049L));
        dependencies.add(new Dependency("org/slf4j/slf4j-api/1.7.25/slf4j-api-1.7.25.jar", 41203L));
        return dependencies;
    }

    @Override
    public byte[] export(Process process) throws IOException {
        return new BpmnExportHelper().exportProcess(process).getBytes();
    }
}
