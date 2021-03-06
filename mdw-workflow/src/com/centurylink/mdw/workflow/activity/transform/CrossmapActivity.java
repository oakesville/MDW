package com.centurylink.mdw.workflow.activity.transform;

import com.centurylink.mdw.activity.ActivityException;
import com.centurylink.mdw.annotations.Activity;
import com.centurylink.mdw.cache.asset.AssetCache;
import com.centurylink.mdw.common.translator.impl.DomDocumentTranslator;
import com.centurylink.mdw.model.JsonObject;
import com.centurylink.mdw.model.asset.Asset;
import com.centurylink.mdw.model.asset.AssetVersionSpec;
import com.centurylink.mdw.model.variable.Variable;
import com.centurylink.mdw.model.workflow.ActivityRuntimeContext;
import com.centurylink.mdw.script.*;
import com.centurylink.mdw.translator.JsonTranslator;
import com.centurylink.mdw.variable.VariableTranslator;
import com.centurylink.mdw.workflow.activity.DefaultActivityImpl;
import com.centurylink.mdw.xml.DomHelper;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.json.JSONObject;
import org.w3c.dom.Document;

import javax.xml.transform.TransformerException;

@Activity(value="Crossmap Activity", icon="com.centurylink.mdw.base/crossmap.png",
        pagelet="com.centurylink.mdw.base/crossmap.pagelet")
public class CrossmapActivity extends DefaultActivityImpl {

    protected static final String MAPPER = "Mapper";
    protected static final String MAPPER_VERSION = "Mapper_assetVersion";
    protected static final String INPUT = "Input";
    protected static final String OUTPUT = "Output";

    @Override
    public Object execute(ActivityRuntimeContext runtimeContext) throws ActivityException {
        try {
            String mapper = getAttributeValueSmart(MAPPER);
            if (mapper == null)
                throw new ActivityException("Missing attribute: " + MAPPER);
            String mapperVer = getAttributeValueSmart(MAPPER_VERSION);
            AssetVersionSpec spec = new AssetVersionSpec(mapper, mapperVer == null ? "0" : mapperVer);
            Asset mapperScript = AssetCache.getAsset(spec);
            if (mapperScript == null)
                throw new ActivityException("Cannot load mapping script: " + spec);
            if (!"groovy".equals(mapperScript.getExtension()))
                throw new ActivityException("Unsupported mapper extension: " + mapperScript.getExtension());

            // input
            String inputAttr = getAttributeValueSmart(INPUT);
            if (inputAttr == null)
                throw new ActivityException("Missing attribute: " + INPUT);
            Variable inputVar = runtimeContext.getProcess().getVariable(inputAttr);
            if (inputVar == null)
                throw new ActivityException("Input variable not defined: " + inputAttr);
            VariableTranslator inputTrans = getPackage().getTranslator(inputVar.getType());
            Object inputObj = getVariableValue(inputAttr);
            if (inputObj == null)
                throw new ActivityException("Input variable is null: " + inputAttr);
            Slurper slurper;
            // XML is always tried first since XML can now be represented as JSON
            if (inputTrans instanceof DomDocumentTranslator) {
                Document input = ((DomDocumentTranslator)inputTrans).toDomDocument(inputObj);
                slurper = new XmlSlurper(inputVar.getName(), DomHelper.toXml((Document)input));
            }
            else if (inputTrans instanceof JsonTranslator) {
                JSONObject input = ((JsonTranslator)inputTrans).toJson(inputObj);
                slurper = new JsonSlurper(inputVar.getName(), input.toString());
            }
            else {
                throw new ActivityException("Unsupported input variable type: " + inputVar.getType());
            }

            // output
            String outputAttr = getAttributeValueSmart(OUTPUT);
            if (outputAttr == null)
                throw new ActivityException("Missing attribute: " + OUTPUT);
            Variable outputVar = runtimeContext.getProcess().getVariable(outputAttr);
            if (outputVar == null)
                throw new ActivityException("Output variable not defined: " + outputVar);
            VariableTranslator outputTrans = getPackage().getTranslator(outputVar.getType());
            Builder builder;
            if (outputTrans instanceof DomDocumentTranslator)
                builder = new XmlBuilder(outputVar.getName());
            else if (outputTrans instanceof JsonTranslator)
                builder = new JsonBuilder(outputVar.getName());
            else
                throw new ActivityException("Unsupported output variable type: " + outputVar.getType());

            runScript(mapperScript.getText(), slurper, builder);

            if (outputTrans instanceof DomDocumentTranslator) {
                Object output = ((DomDocumentTranslator)outputTrans).fromDomNode(DomHelper.toDomDocument(builder.getString()));
                setVariableValue(outputVar.getName(), output);
            }
            else if (outputTrans instanceof JsonTranslator) {
                Object output = ((JsonTranslator)outputTrans).fromJson(new JsonObject(builder.getString()), getDocumentType(outputVar.getName()));
                setVariableValue(outputVar.getName(), output);
            }

            return null;
        }
        catch (Exception ex) {
            throw new ActivityException(ex.getMessage(), ex);
        }
    }

    /**
     * Invokes the builder object for creating new output variable value.
     */
    protected void runScript(String mapperScript, Slurper slurper, Builder builder)
            throws ActivityException, TransformerException {

        CompilerConfiguration compilerConfig = new CompilerConfiguration();
        compilerConfig.setScriptBaseClass(CrossmapScript.class.getName());

        Binding binding = new Binding();
        binding.setVariable("runtimeContext", getRuntimeContext());
        binding.setVariable(slurper.getName(), slurper.getInput());
        binding.setVariable(builder.getName(), builder);
        GroovyShell shell = new GroovyShell(getPackage().getClassLoader(), binding, compilerConfig);
        Script gScript = shell.parse(mapperScript);
        gScript.run();
    }

}
