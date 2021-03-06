package com.centurylink.mdw.drools;

import com.centurylink.mdw.activity.ActivityException;
import com.centurylink.mdw.activity.types.RuleActivity;
import com.centurylink.mdw.annotations.Activity;
import com.centurylink.mdw.cache.asset.PackageCache;
import com.centurylink.mdw.config.PropertyException;
import com.centurylink.mdw.model.asset.AssetVersionSpec;
import com.centurylink.mdw.model.variable.Variable;
import com.centurylink.mdw.model.variable.VariableInstance;
import com.centurylink.mdw.model.workflow.Package;
import com.centurylink.mdw.model.workflow.Process;
import com.centurylink.mdw.util.log.StandardLogger.LogLevel;
import com.centurylink.mdw.util.timer.Tracked;
import com.centurylink.mdw.workflow.activity.DefaultActivityImpl;
import org.apache.commons.lang.StringUtils;
import org.kie.api.KieBase;
import org.kie.api.runtime.StatelessKieSession;
import org.kie.internal.command.CommandFactory;

import java.util.*;

@Tracked(LogLevel.TRACE)
@Activity(value="Drools Activity", category=RuleActivity.class, icon="com.centurylink.mdw.drools/drools.jpg",
        pagelet="com.centurylink.mdw.drools/drools.pagelet")
public class DroolsActivity extends DefaultActivityImpl implements RuleActivity {

    public static final String KNOWLEDGE_BASE = "KnowledgeBase";
    public static final String KNOWLEDGE_BASE_ASSET_VERSION = "KnowledgeBase_assetVersion";
    public static final String RULE_VERSION_VAR = "RuleVersionVar";


    @Override
    @SuppressWarnings("unchecked")
    public void execute() throws ActivityException {

        String knowledgeBaseName = null;
        String knowledgeBaseVersion = null;
        try {
            knowledgeBaseName = getAttributeValueSmart(KNOWLEDGE_BASE);
            knowledgeBaseVersion = getAttributeValueSmart(KNOWLEDGE_BASE_ASSET_VERSION);
        }
        catch (PropertyException ex) {
            throw new ActivityException(ex.getMessage(), ex);
        }

        if (StringUtils.isBlank(knowledgeBaseName))
            throw new ActivityException("Missing attribute: " + KNOWLEDGE_BASE);

        KieBase knowledgeBase = getKnowledgeBase(knowledgeBaseName, knowledgeBaseVersion);
        if (knowledgeBase == null)
            throw new ActivityException("Cannot load KnowledgeBase: " + knowledgeBaseName);

        // TODO stateful session option
        StatelessKieSession kSession = knowledgeBase.newStatelessKieSession();

        List<Object> facts = new ArrayList<>();
        Map<String,Object> values = new HashMap<>();

        for (VariableInstance variableInstance : getParameters()) {
            Object value = getVariableValue(variableInstance.getName());
            values.put(variableInstance.getName(), value);
        }
        logDebug("Drools values: " + values);

        facts.add(values);
        logDebug("Drools facts: " + facts);

        setGlobalValues(kSession);

        kSession.execute(CommandFactory.newInsertElements(facts));

        String temp = getAttributeValue(OUTPUTDOCS);
        setOutputDocuments(getAttributes().containsKey(OUTPUTDOCS) ? getAttributes().getList(OUTPUTDOCS).toArray(new String[0]) : new String[0]);

        // TODO handle document variables
        Process processVO = getProcessDefinition();
        for (Variable variable : processVO.getVariables()) {
            Object newValue = values.get(variable.getName());
            if (newValue != null)
                setVariableValue(variable.getName(), variable.getType(), newValue);
        }
    }

    /**
     * Get knowledge base with no modifiers.
     */
    protected KieBase getKnowledgeBase(String name, String version) throws ActivityException {
        return getKnowledgeBase(name, version, null);
    }

    /**
     * Returns the asset based on specified version/range whose attributes match the custom attribute
     * Override to apply additional or non-standard conditions.
     */
    protected KieBase getKnowledgeBase(String name, String assetVersion, String modifier) throws ActivityException {
        KnowledgeBaseAsset kbrs;
        if (assetVersion == null)
            kbrs = DroolsKnowledgeBaseCache.getKnowledgeBaseAsset(name, modifier, getClassLoader());
        else
            kbrs = DroolsKnowledgeBaseCache.getKnowledgeBaseAsset(new AssetVersionSpec(name, assetVersion), modifier, getClassLoader());

        if (kbrs == null) {
            return null;
        }
        else {
            logInfo("Using Knowledge Base: " + kbrs.getAsset().getLabel());

            String versionLabelVarName = getAttributeValue(RULE_VERSION_VAR);
            if (versionLabelVarName != null)
                setParameterValue(versionLabelVarName, kbrs.getAsset().getLabel());
            return kbrs.getKnowledgeBase();
        }
    }

    /**
     * Get the class loader based on package bundle version spec
     * default is mdw-workflow loader
     * @return
     */
    protected ClassLoader getClassLoader() {
        ClassLoader loader = null;
        Process process = getMainProcessDefinition();
        Package pkg = process == null ? null : PackageCache.getPackage(process.getPackageName());
        if (pkg != null) {
            loader = pkg.getClassLoader();
        }
        if (loader == null) {
            loader = getClass().getClassLoader();
        }
        return loader;
    }

    protected void setGlobalValues(StatelessKieSession knowledgeSession) throws ActivityException {
        knowledgeSession.setGlobal("activity", this); // TODO deprecate
        knowledgeSession.setGlobal("runtimeContext", getRuntimeContext());
        knowledgeSession.setGlobal("now", new Date());
    }

    // expose this publicly
    public void setReturnCode(String code) {
        super.setReturnCode(code);
    }

}
