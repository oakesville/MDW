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
package com.centurylink.mdw.services.process;

import com.centurylink.mdw.activity.ActivityException;
import com.centurylink.mdw.activity.types.*;
import com.centurylink.mdw.cache.asset.PackageCache;
import com.centurylink.mdw.common.MdwException;
import com.centurylink.mdw.common.service.Query;
import com.centurylink.mdw.config.PropertyManager;
import com.centurylink.mdw.constant.*;
import com.centurylink.mdw.dataaccess.DataAccessException;
import com.centurylink.mdw.dataaccess.DatabaseAccess;
import com.centurylink.mdw.model.JsonObject;
import com.centurylink.mdw.model.event.EventInstance;
import com.centurylink.mdw.model.event.EventType;
import com.centurylink.mdw.model.event.EventWaitInstance;
import com.centurylink.mdw.model.event.InternalEvent;
import com.centurylink.mdw.model.monitor.ScheduledEvent;
import com.centurylink.mdw.model.variable.Document;
import com.centurylink.mdw.model.variable.DocumentReference;
import com.centurylink.mdw.model.variable.Variable;
import com.centurylink.mdw.model.variable.VariableInstance;
import com.centurylink.mdw.model.workflow.Package;
import com.centurylink.mdw.model.workflow.Process;
import com.centurylink.mdw.model.workflow.*;
import com.centurylink.mdw.model.workflow.WorkStatus.InternalLogMessage;
import com.centurylink.mdw.monitor.MonitorRegistry;
import com.centurylink.mdw.monitor.OfflineMonitor;
import com.centurylink.mdw.monitor.ProcessMonitor;
import com.centurylink.mdw.service.data.activity.ImplementorCache;
import com.centurylink.mdw.service.data.process.EngineDataAccess;
import com.centurylink.mdw.service.data.process.ProcessCache;
import com.centurylink.mdw.services.*;
import com.centurylink.mdw.services.event.ScheduledEventQueue;
import com.centurylink.mdw.services.messenger.InternalMessenger;
import com.centurylink.mdw.translator.VariableTranslator;
import com.centurylink.mdw.util.ServiceLocatorException;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;
import com.centurylink.mdw.util.log.StandardLogger.LogLevel;
import com.centurylink.mdw.util.timer.Tracked;
import com.centurylink.mdw.util.timer.TrackingTimer;
import org.json.JSONObject;

import javax.jms.JMSException;
import javax.naming.NamingException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.centurylink.mdw.model.workflow.ProcessRuntimeContext.isExpression;

class ProcessExecutorImpl {

    protected static StandardLogger logger = LoggerUtil.getStandardLogger();
    private EngineLogger engineLogger;

    private static boolean uniqueMasterRequestId = PropertyManager.getBooleanProperty("mdw.process.uniqueMasterRequestId", false);

    protected EngineDataAccess edao;
    private InternalMessenger internalMessenger;
    private final boolean inService;
    boolean activityTimings;

    ProcessExecutorImpl(EngineDataAccess edao, InternalMessenger internalMessenger, boolean forServiceProcess) {
        this.edao = edao;
        this.internalMessenger = internalMessenger;
        inService = forServiceProcess;
        engineLogger = new EngineLogger(logger, edao.getPerformanceLevel());
    }

    final EngineDataAccess getDataAccess() {
        return edao;
    }

    final DatabaseAccess getDatabaseAccess() {
        return edao.getDatabaseAccess();
    }

    ActivityInstance createActivityInstance(Long pActivityId, Long procInstId)
            throws SQLException, DataAccessException {
        ActivityInstance ai = new ActivityInstance();
        ai.setActivityId(pActivityId);
        ai.setProcessInstanceId(procInstId);
        ai.setStatusCode(WorkStatus.STATUS_IN_PROGRESS);
        edao.createActivityInstance(ai);
        return ai;
    }

    TransitionInstance createTransitionInstance(
            Transition transition, Long processInstId)
            throws DataAccessException {
        try {
            TransitionInstance transInst = new TransitionInstance();
            transInst.setTransitionID(transition.getId());
            transInst.setProcessInstanceID(processInstId);
            transInst.setStatusCode(TransitionStatus.STATUS_INITIATED);
            transInst.setDestinationID(transition.getToId());
            edao.createTransitionInstance(transInst);
            return transInst;
        } catch (SQLException e) {
            throw new DataAccessException(0, e.getMessage(), e);
        }
    }

    VariableInstance createVariableInstance(ProcessInstance pi,
            String varname, Object value)
            throws SQLException,DataAccessException {
        Process processVO = this.getMainProcessDefinition(pi);
        Variable variableVO = processVO.getVariable(varname);
        if (variableVO==null) {
            throw new DataAccessException("Variable "
                    + varname + " is not defined for process " + processVO.getId());
        }
        VariableInstance var = new VariableInstance();
        var.setName(variableVO.getName());
        var.setVariableId(variableVO.getId());
        var.setType(variableVO.getType());
        if (value instanceof String)
            var.setStringValue((String)value);
        else
            var.setData(value);
        if (pi.isEmbedded() || (!pi.getProcessId().equals(processVO.getId()) && pi.getInstanceDefinitionId() <= 0))
            edao.createVariableInstance(var, pi.getOwnerId());
        else
            edao.createVariableInstance(var, pi.getId());
        return var;
    }

    DocumentReference createDocument(String type, String ownerType, Long ownerId,
            Object doc) throws DataAccessException {
        return createDocument(type, ownerType, ownerId, null, null, doc);
    }

    DocumentReference createDocument(String type, String ownerType, Long ownerId,
            Integer statusCode, String statusMessage, Object doc) throws DataAccessException {
        return createDocument(type, ownerType, ownerId, statusCode, statusMessage, null, doc);
    }

    DocumentReference createDocument(String type, String ownerType, Long ownerId,
            Integer statusCode, String statusMessage, String path, Object doc) throws DataAccessException {
        DocumentReference docref;
        try {
            Document docvo = new Document();
            if (doc instanceof String)
                docvo.setContent((String)doc);
            else
                docvo.setObject(doc, type);
            docvo.setDocumentType(type);
            docvo.setOwnerType(ownerType);
            docvo.setOwnerId(ownerId);
            docvo.setStatusCode(statusCode);
            docvo.setStatusMessage(statusMessage);
            docvo.setPath(path);
            edao.createDocument(docvo);
            docref = new DocumentReference(docvo.getDocumentId());
        } catch (Exception e) {
            throw new DataAccessException(0, e.getMessage(), e);
        }
        return docref;
    }

    Document getDocument(DocumentReference docref, boolean forUpdate) throws DataAccessException {
        try {
            return edao.getDocument(docref.getDocumentId(), forUpdate);
        } catch (SQLException e) {
            throw new DataAccessException(-1, e.getMessage(), e);
        }
    }

    /**
     * Does not work for remote documents
     */
    Document loadDocument(DocumentReference docref, boolean forUpdate)
            throws DataAccessException {
        try {
            return edao.loadDocument(docref.getDocumentId(), forUpdate);
        } catch (SQLException e) {
            throw new DataAccessException(-1, e.getMessage(), e);
        }
    }

    void updateDocumentContent(DocumentReference docref, Object doc, String type, Package pkg) throws DataAccessException {
        try {
            Document docvo = edao.getDocument(docref.getDocumentId(), false);
            if (doc instanceof String)
                docvo.setContent((String)doc);
            else
                docvo.setObject(doc, type);
            edao.updateDocumentContent(docvo.getDocumentId(), docvo.getContent(pkg));
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to update document content", e);
        }
    }

    private List<VariableInstance> convertParameters(Map<String,String> eventParams,
            Process process, Long procInstId) throws ProcessException, DataAccessException {
        List<VariableInstance> vars = new ArrayList<>();
        if (eventParams == null || eventParams.isEmpty()) {
            return vars;
        }
        for (String varName : eventParams.keySet()) {
            Variable variable = process.getVariable(varName);
            if (variable == null) {
                String msg = "there is no variable named " + varName
                        + " in process with ID " + process.getId()
                        + " for parameter binding";
                throw new ProcessException(msg);
            }
            VariableInstance var = new VariableInstance();
            var.setName(variable.getName());
            var.setVariableId(variable.getId());
            var.setType(variable.getType());
            String value = eventParams.get(varName);
            if (value != null && value.length() > 0) {
                if (VariableTranslator.isDocumentReferenceVariable(getPackage(process), var.getType())) {
                    if (value.startsWith("DOCUMENT:")) var.setStringValue(value);
                    else {
                        DocumentReference docref = this.createDocument(var.getType(),
                                OwnerType.PROCESS_INSTANCE, procInstId, value);
                        var.setData(docref);
                    }
                } else var.setStringValue(value);
                vars.add(var);    // only create variable instances when value is not null
            }
            // vars.add(var);    // if we put here, we create variables regardless if value is null
        }

        return vars;
    }

    /**
     * Create a process instance. The status is PENDING_PROCESS
     */
    ProcessInstance createProcessInstance(Long processId, String ownerType,
            Long ownerId, String secondaryOwnerType, Long secondaryOwnerId,
            String masterRequestId, Map<String,String> parameters, String label, String template)
            throws ProcessException, DataAccessException
    {
        ProcessInstance pi = null;
        try {
            Process processVO;
            if (OwnerType.MAIN_PROCESS_INSTANCE.equals(ownerType)) {
                ProcessInstance parentPi = getDataAccess().getProcessInstance(ownerId);
                Process parentProcdef = ProcessCache.getProcess(parentPi.getProcessId());
                processVO = parentProcdef.getSubProcess(processId);
                pi = new ProcessInstance(parentPi.getProcessId(), processVO.getName());
                String comment = processId.toString();
                if (parentPi.getInstanceDefinitionId() > 0L)  // Indicates instance definition
                    comment += "|HasInstanceDef|" + parentPi.getInstanceDefinitionId();
                pi.setComment(comment);
            } else {
                if (uniqueMasterRequestId && !(OwnerType.PROCESS_INSTANCE.equals(ownerType) || OwnerType.ERROR.equals(ownerType))) {
                    // Check for uniqueness of master request id before creating top level process instance, if enabled
                    List<ProcessInstance> list = edao.getProcessInstancesByMasterRequestId(masterRequestId);
                    if (list != null && list.size() > 0) {
                        String msg = "Could not launch process instance for " + (label != null ? label : template) + " because Master Request ID " + masterRequestId + " is not unique";
                        logger.error(msg);
                        throw new ProcessException(msg);
                    }
                }
                processVO = ProcessCache.getProcess(processId);
                pi = new ProcessInstance(processId, processVO.getName());
            }
            pi.setOwner(ownerType);
            pi.setOwnerId(ownerId);
            pi.setSecondaryOwner(secondaryOwnerType);
            pi.setSecondaryOwnerId(secondaryOwnerId);
            pi.setMasterRequestId(masterRequestId);
            pi.setStatusCode(WorkStatus.STATUS_PENDING_PROCESS);
            if (label != null)
                pi.setComment(label);
            if (template != null)
                pi.setTemplate(template);
            edao.createProcessInstance(pi);
            createVariableInstancesFromEventMessage(pi, parameters);
        } catch (IOException ex) {
            throw new ProcessException("Cannot load process " + processId, ex);

        } catch (SQLException ex) {
            if (pi != null && pi.getId() != null && pi.getId() > 0L)
                try {
                    edao.setProcessInstanceStatus(pi.getId(), WorkStatus.STATUS_FAILED);
                } catch (SQLException e) { logger.severeException("Exception while updating process status to 'Failed'", e);}
            throw new DataAccessException(-1, ex.getMessage(), ex);
        }
        return pi;
    }

    private void createVariableInstancesFromEventMessage(ProcessInstance pi,
            Map<String,String> parameters) throws ProcessException, DataAccessException, SQLException {
        Process processVO = getProcessDefinition(pi);
        pi.setVariables(convertParameters(parameters, processVO, pi.getId()));
        for (VariableInstance var : pi.getVariables()) {
            edao.createVariableInstance(var, pi.getId());
            if (var.isDocument()) {
                DocumentReference docRef = new DocumentReference(var.getStringValue());
                updateDocumentInfo(docRef, var.getType(), OwnerType.VARIABLE_INSTANCE, var.getInstanceId(), null, null);
            }

        }
    }

    void updateDocumentInfo(DocumentReference docref, String documentType, String ownerType,
            Long ownerId, Integer statusCode, String statusMessage) throws DataAccessException {
        try {
            boolean dirty = false;
            Document docvo = edao.getDocument(docref.getDocumentId(), false);
            if (documentType != null && !documentType.equalsIgnoreCase(docvo.getDocumentType())) {
                docvo.setDocumentType(documentType);
                dirty = true;
            }
            if (ownerId != null && !ownerId.equals(docvo.getOwnerId())) {
                // DO NOT UPDATE THE OWNER_ID IF IT'S A PROCESS VARIABLE ALREADY OWNED BY DIFFERENT PROCESS INSTANCE
                if (!("VARIABLE_INSTANCE".equalsIgnoreCase(ownerType) && ownerType.equalsIgnoreCase(docvo.getOwnerType()) && docvo.getOwnerId() > 0L)) {
                    docvo.setOwnerId(ownerId);
                    dirty = true;
                }
            }
            if (ownerType != null && !ownerType.equalsIgnoreCase(docvo.getOwnerType())) {
                if (edao.getDocumentDbAccess() != null)
                    edao.getDocumentDbAccess().updateDocumentDbOwnerType(docvo, ownerType);
                docvo.setOwnerType(ownerType);
                dirty = true;
            }
            if (statusCode != null && !statusCode.equals(docvo.getStatusCode())) {
                docvo.setStatusCode(statusCode);
                dirty = true;
            }
            if (statusMessage != null && !statusMessage.equals(docvo.getStatusMessage())) {
                docvo.setStatusMessage(statusMessage);
                dirty = true;
            }
            if (dirty)
                edao.updateDocumentInfo(docvo);
        } catch (SQLException e) {
            throw new DataAccessException(-1, e.getMessage(), e);
        }
    }

    void cancelEventWaitInstances(Long activityInstanceId)
            throws DataAccessException {
        try {
            getDataAccess().removeEventWaitForActivityInstance(activityInstanceId, "Cancel due to timeout");
        } catch (Exception e) {
            throw new DataAccessException(0, "Failed to cancel event waits", e);
        }
    }

    String getServiceProcessResponse(Long procInstId, String varname)
            throws DataAccessException {
        try {
            VariableInstance varinst;
            if (varname==null) {
                varinst = getDataAccess().getVariableInstance(procInstId, VariableConstants.RESPONSE);
                if (varinst==null) varinst = getDataAccess().getVariableInstance(procInstId, VariableConstants.MASTER_DOCUMENT);
                if (varinst==null) varinst = getDataAccess().getVariableInstance(procInstId, VariableConstants.REQUEST);
            } else {
                varinst = getDataAccess().getVariableInstance(procInstId, varname);
            }
            if (varinst==null) return null;
            if (varinst.isDocument()) {
                Document docvo = getDocument((DocumentReference)varinst.getData(), false);
                return docvo.getContent(null);
            } else return varinst.getStringValue();
        } catch (SQLException e) {
            throw new DataAccessException(0, "Failed to get value for variable " + varname, e);
        }
    }

    void updateProcessInstanceStatus(Long pProcInstId, Integer status)
            throws DataAccessException,ProcessException {
        try {
            getDataAccess().setProcessInstanceStatus(pProcInstId, status);
            if (status.equals(WorkStatus.STATUS_COMPLETED) ||
                    status.equals(WorkStatus.STATUS_CANCELLED) ||
                    status.equals(WorkStatus.STATUS_FAILED)) {
                getDataAccess().removeEventWaitForProcessInstance(pProcInstId);
            }
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to update process instance status", e);
        }
    }

    protected Process getProcessDefinition(ProcessInstance processInstance) {
        Process process = null;
        if (processInstance.getInstanceDefinitionId() > 0L)
            process = ProcessCache.getInstanceDefinition(processInstance.getProcessId(), processInstance.getInstanceDefinitionId());
        if (process == null) {
            try {
                process = ProcessCache.getProcess(processInstance.getProcessId());
            } catch (IOException ex) {
                logger.error("Error loading process definition for instance " + processInstance.getId(), ex);
            }
        }
        if (processInstance.isEmbedded() && process != null)
            process = process.getSubProcess(new Long(processInstance.getComment()));
        return process;
    }

    protected Process getMainProcessDefinition(ProcessInstance processInstance) {
        Process procdef = null;
        if (processInstance.getInstanceDefinitionId() > 0L)
            procdef = ProcessCache.getInstanceDefinition(processInstance.getProcessId(), processInstance.getInstanceDefinitionId());
        if (procdef == null) {
            try {
                procdef = ProcessCache.getProcess(processInstance.getProcessId());
            } catch (IOException ex) {
                logger.severeException("Error loading definition for process instance " + processInstance.getId(), ex);
            }
        }
        return procdef;
    }

    boolean deleteInternalEvent(String eventName)
            throws DataAccessException {
        if (eventName == null)
            return false;
        try {
            int count = getDataAccess().deleteEventInstance(eventName);
            return count > 0;
        } catch (SQLException e) {
            throw new DataAccessException(0, "Failed to delete internal event" + eventName, e);
        }
    }

    InternalMessenger getInternalMessenger() {
        return internalMessenger;
    }

    /**
     * Handles the work Transitions for the passed in collection of Items
     */
    void createTransitionInstances(ProcessInstance processInstance, List<Transition> transitions, Long fromActInstId)
            throws ProcessException, IOException {
        TransitionInstance transInst;
        for (Transition transition : transitions) {
            try {
                if (tooManyTransitions(transition, processInstance)) {
                    // Look for a error transition at this time
                    // In case we find it, raise the error event
                    // Otherwise do not do anything
                    handleWorkTransitionError(processInstance, transition.getId(), fromActInstId);
                } else {
                    transInst = createTransitionInstance(transition, processInstance.getId());
                    String tag = EngineLogger.logtag(processInstance.getProcessId(), processInstance.getId(), transInst);
                    String msg = InternalLogMessage.TRANSITION_INIT.message + " from " + transition.getFromId() + " to " + transition.getToId();
                    engineLogger.info(tag, processInstance.getId(), msg);

                    InternalEvent jmsmsg;
                    jmsmsg = InternalEvent.createActivityStartMessage(
                            transition.getToId(), processInstance.getId(),
                            transInst.getTransitionInstanceID(), processInstance.getMasterRequestId(),
                            transition.getLabel());
                    String msgid = ScheduledEvent.INTERNAL_EVENT_PREFIX + processInstance.getId()
                            + "start" + transition.getToId() + "by" + transInst.getTransitionInstanceID();
                    int delay = getTransitionDelay(transition, processInstance);
                    if (delay > 0)
                        sendDelayedInternalEvent(jmsmsg, delay, msgid, false);
                    else
                        sendInternalEvent(jmsmsg);
                }
            } catch (SQLException | MdwException ex) {
                throw new ProcessException(-1, ex.getMessage(), ex);
            }
        }
    }

    public int getTransitionDelay(Transition transition, ProcessInstance processInstance) {
        int secDelay = 0;
        String delayAttr = transition.getAttribute(WorkTransitionAttributeConstant.TRANSITION_DELAY);
        if (delayAttr != null) {
            if (isExpression(delayAttr)) {
                String expr = delayAttr.endsWith("s") ? delayAttr.substring(0, delayAttr.length() - 1) : delayAttr;
                secDelay = (Integer) evaluate(expr, processInstance);
            }
            else {
                // moved from Transition.getTransitionDelay()
                int k, n=delayAttr.length();
                for (k=0; k<n; k++) {
                    if (!Character.isDigit(delayAttr.charAt(k))) break;
                }
                if (k<n) {
                    String unit = delayAttr.substring(k).trim();
                    delayAttr = delayAttr.substring(0,k);
                    if (unit.startsWith("s")) secDelay = Integer.parseInt(delayAttr);
                    else if (unit.startsWith("h")) secDelay = 3600 * Integer.parseInt(delayAttr);
                    else secDelay = 60 * Integer.parseInt(delayAttr);
                } else secDelay = 60 * Integer.parseInt(delayAttr);
            }
        }
        return secDelay;
    }

    private boolean tooManyTransitions(Transition trans, ProcessInstance processInstance)
            throws SQLException {
        if (inService)
            return false;
        int retryCount = -1;
        String retryAttr = trans.getAttribute(WorkTransitionAttributeConstant.TRANSITION_RETRY_COUNT);
        if (retryAttr != null) {
            if (isExpression(retryAttr))
                retryCount = (Integer) evaluate(retryAttr, processInstance);
            else
                retryCount = Integer.parseInt(retryAttr);
        }
        if (retryCount < 0)
            return false;
        int count = edao.countTransitionInstances(processInstance.getId(), trans.getId());
        if (count > 0 && count >= retryCount) {
            String msg = "Transition " + trans.getId() + " not made - exceeded allowed retry count of " + retryCount;
            // log as exception since this message is often overlooked
            logger.severeException(msg, new ProcessException(msg));
            return true;
        } else {
            return false;
        }
    }

    /**
     * Supports simple expressions only (does not deserialize documents).
     */
    private Object evaluate(String expression, ProcessInstance processInstance) {
        Process process = getProcessDefinition(processInstance);
        Package pkg = getPackage(getMainProcessDefinition(processInstance));
        Map<String,Object> vars = new HashMap<>();
        for (VariableInstance vi : processInstance.getVariables())
            vars.put(vi.getName(), vi.getData());
        return new ProcessRuntimeContext(null, pkg, process, processInstance,
                getDataAccess().getPerformanceLevel(), isInService(), vars).evaluate(expression);
    }

    private void handleWorkTransitionError(ProcessInstance processInstance, Long workTransitionId,
            Long fromActInstId) throws ProcessException, DataAccessException, SQLException, IOException {
        edao.setProcessInstanceStatus(processInstance.getId(), WorkStatus.STATUS_WAITING);
        Process processVO = getMainProcessDefinition(processInstance);
        Process embeddedProcdef = processVO.findSubprocess(EventType.ERROR, null);
        while (embeddedProcdef == null && processInstance.getOwner().equals(OwnerType.PROCESS_INSTANCE)) {
            processInstance = edao.getProcessInstance(processInstance.getOwnerId());
            processVO = getMainProcessDefinition(processInstance);
            embeddedProcdef = processVO.findSubprocess(EventType.ERROR, null);
        }
        if (embeddedProcdef == null) {
            logger.warn("Error subprocess does not exist. Transition failed. TransitionId-->"
                    + workTransitionId + " ProcessInstanceId-->" + processInstance.getId());
            return;
        }
        String msg = "Transition to error subprocess " + embeddedProcdef.getQualifiedName();
        engineLogger.info(processInstance.getProcessId(), processInstance.getId(), processInstance.getMasterRequestId(), msg);
        String secondaryOwnerType;
        Long secondaryOwnerId;
        if (fromActInstId==null || fromActInstId == 0L) {
            secondaryOwnerType = OwnerType.WORK_TRANSITION;
            secondaryOwnerId = workTransitionId;
        } else {
            secondaryOwnerType = OwnerType.ACTIVITY_INSTANCE;
            secondaryOwnerId = fromActInstId;
        }
        String ownerType = OwnerType.MAIN_PROCESS_INSTANCE;
        ProcessInstance procInst = createProcessInstance(embeddedProcdef.getId(),
                ownerType, processInstance.getId(), secondaryOwnerType, secondaryOwnerId,
                processInstance.getMasterRequestId(), null, null, null);
        startProcessInstance(procInst, 0);
    }

    /**
     * Starting a process instance, which has been created already.
     * The method sets the status to "In Progress",
     * find the start activity, and sends an internal message to start the activity
     *
     * @param processInstanceVO process instance vo.
     */
    void startProcessInstance(ProcessInstance processInstanceVO, int delay)
            throws ProcessException {
        try {
            Process process = getProcessDefinition(processInstanceVO);
            edao.setProcessInstanceStatus(processInstanceVO.getId(), WorkStatus.STATUS_PENDING_PROCESS);
            // setProcessInstanceStatus will really set to STATUS_IN_PROGRESS - hint to set START_DT as well
            if (logger.isInfoEnabled()) {
                String msg = InternalLogMessage.PROCESS_START + " - " + process.getQualifiedName()
                        + (processInstanceVO.isEmbedded() ? (" (embedded process " + process.getId() + ")") : ("/" + process.getVersionString()));
                engineLogger.info(processInstanceVO.getProcessId(), processInstanceVO.getId(), processInstanceVO.getMasterRequestId(), msg);
                engineLogger.info(processInstanceVO.getProcessId(), processInstanceVO.getId(), processInstanceVO.getMasterRequestId(), "Performance level = " + engineLogger.getPerformanceLevel());
            }
            notifyMonitors(processInstanceVO, InternalLogMessage.PROCESS_START);
            // get start activity ID
            Long startActivityId;
            if (processInstanceVO.isEmbedded()) {
                edao.setProcessInstanceStatus(processInstanceVO.getId(), WorkStatus.STATUS_PENDING_PROCESS);
                startActivityId = process.getStartActivity().getId();
            } else {
                Activity startActivity = process.getStartActivity();
                if (startActivity == null) {
                    throw new ProcessException("Transition has not been defined for START event! ProcessID = " + process.getId());
                }
                startActivityId = startActivity.getId();
            }
            InternalEvent event = InternalEvent.createActivityStartMessage(
                    startActivityId, processInstanceVO.getId(),
                    null, processInstanceVO.getMasterRequestId(),
                    EventType.EVENTNAME_START + ":");
            if (delay > 0) {
                String msgid = ScheduledEvent.INTERNAL_EVENT_PREFIX + processInstanceVO.getId()
                        + "start" + startActivityId;
                this.sendDelayedInternalEvent(event, delay, msgid, false);
            } else sendInternalEvent(event);
        }
        catch (Exception ex) {
            logger.severeException(ex.getMessage(), ex);
            throw new ProcessException(ex.getMessage());
        }
    }

    ///// execute activity

    /**
     * determine if activity needs to wait (such as synchronous
     * process invocation, wait activity, synchronization)
     */
    private boolean activityNeedsWait(GeneralActivity activity)
            throws ActivityException {
        if (activity instanceof SuspendableActivity)
            return ((SuspendableActivity) activity).needSuspend();
        return false;
    }

    /**
     * Reports the error status of the activity instance to the activity manager
     */
    void failActivityInstance(InternalEvent event,
            ProcessInstance processInst, Long activityId, Long activityInstId,
            BaseActivity activity, Throwable cause) throws DataAccessException, MdwException, SQLException {

        String tag = EngineLogger.logtag(processInst.getProcessId(), processInst.getId(), activityId, activityInstId);
        String msg = "Failed to execute activity - " + cause.getClass().getName();
        engineLogger.error(processInst.getProcessId(), processInst.getId(), activityId, activityInstId, msg, cause);

        String compCode = null;
        String statusMsg = buildStatusMessage(cause);
        try {
            ActivityInstance actInstVO = null;
            if (activity != null && activityInstId != null) {
                activity.setReturnMessage(statusMsg);
                actInstVO = edao.getActivityInstance(activityInstId);
                failActivityInstance(actInstVO, statusMsg, processInst, tag, cause.getClass().getName());
                compCode = activity.getReturnCode();
            }
            if (!AdapterActivity.COMPCODE_AUTO_RETRY.equals(compCode)) {
                DocumentReference docRef = createActivityExceptionDocument(processInst, actInstVO, activity, cause);
                InternalEvent outgoingMsg =
                        InternalEvent.createActivityErrorMessage(activityId, activityInstId, processInst.getId(), compCode,
                                event.getMasterRequestId(), statusMsg.length() > 2000 ? statusMsg.substring(0, 1999) : statusMsg, docRef.getDocumentId());
                sendInternalEvent(outgoingMsg);
            }
        }
        catch (Exception ex) {
            logger.severeException(ex.getMessage(), ex);
            ActivityLogger.persist(processInst.getId(), activityInstId, LogLevel.ERROR, ex.getMessage(), ex);
            throw ex;
        }
    }

    private String buildStatusMessage(Throwable t) {
        if (t == null)
            return "";
        StringBuilder message = new StringBuilder(t.toString());
        String v = PropertyManager.getProperty("MDWFramework.WorkflowEngine/ActivityStatusMessage.ShowStackTrace");
        boolean includeStackTrace = !"false".equalsIgnoreCase(v);
        if (includeStackTrace) {
            // get the root cause
            Throwable cause = t;
            while (cause.getCause() != null)
                cause = cause.getCause();
            if (t != cause)
                message.append("\nCaused by: ").append(cause);
            for (StackTraceElement element : cause.getStackTrace()) {
                message.append("\n").append(element.toString());
            }
        }

        if (message.length() > 4000) {
            return message.toString().substring(0, 3998);
        }

        return message.toString();
    }

    void cancelActivityInstance(ActivityInstance actInst,
            ProcessInstance procinst, String statusMsg) throws DataAccessException, SQLException {
        String logtag = EngineLogger.logtag(procinst.getProcessId(), procinst.getId(), actInst.getActivityId(), actInst.getId());
        try {
            cancelActivityInstance(actInst, statusMsg, procinst, logtag);
        } catch (Exception e) {
            engineLogger.error(procinst.getProcessId(), procinst.getId(), actInst.getActivityId(), actInst.getId(), e.getMessage(), e);
            throw e;
        }
    }

    void holdActivityInstance(ActivityInstance actInst, Long procId) throws DataAccessException, SQLException {
        String logtag = EngineLogger.logtag(procId, actInst.getProcessInstanceId(), actInst.getActivityId(), actInst.getId());
        try {
            holdActivityInstance(actInst, logtag);
        } catch (Exception e) {
            String msg = "Exception thrown during holdActivityInstance";
            engineLogger.error(procId, actInst.getProcessInstanceId(), actInst.getActivityId(), actInst.getId(), msg, e);
            throw e;
        }
    }

    private ActivityInstance waitForActivityDone(ActivityInstance actInst)
            throws DataAccessException, InterruptedException, SQLException {
        int max_retry = 10;
        int retry_interval = 2;
        int count = 0;
        while (count<max_retry && actInst.getStatusCode()==WorkStatus.STATUS_IN_PROGRESS) {
            logger.debug("wait for sync activity to finish: " + actInst.getId());
            Thread.sleep(retry_interval*1000L);
            actInst = getDataAccess().getActivityInstance(actInst.getId());
            count++;
        }
        return actInst;
    }

    ActivityRuntime prepareActivityInstance(InternalEvent event, ProcessInstance procInst) throws ProcessException {
        try {
            // for asynch engine, procInst is always null
            ActivityRuntime ar = new ActivityRuntime();
            Long activityId = event.getWorkId();
            Long workTransInstanceId = event.getTransitionInstanceId();

            // check if process instance is still alive
            ar.procinst = procInst;
            if (WorkStatus.STATUS_CANCELLED.equals(ar.procinst.getStatusCode())
                    || WorkStatus.STATUS_COMPLETED.equals(ar.procinst.getStatusCode())) {
                ar.startCase = ActivityRuntime.STARTCASE_PROCESS_TERMINATED;
                return ar;
            }

            Process processVO = getProcessDefinition(ar.procinst);
            Activity actVO = processVO.getActivity(activityId);
            Package pkg = PackageCache.getPackage(getMainProcessDefinition(procInst).getPackageName());
            try {
                ar.activity = (BaseActivity)getActivityInstance(pkg, actVO.getImplementor());
            } catch (Throwable e) {
                String tag = EngineLogger.logtag(procInst.getProcessId(), procInst.getId(), activityId, 0L);
                String msg = "Failed to create activity implementor instance";
                engineLogger.error(tag, procInst.getId(),null, msg, e);
                ar.activity = null;
            }
            boolean isSyncActivity = ar.activity instanceof SynchronizationActivity;
            if (isSyncActivity)
                getDataAccess().lockProcessInstance(procInst.getId());

            List<ActivityInstance> actInsts;
            if (this.inService)
                actInsts = null;
            else
                actInsts = getDataAccess().getActivityInstances(activityId, procInst.getId(), true, isSyncActivity);
            if (actInsts == null || actInsts.isEmpty()) {
                // create activity instance and prepare it
                ar.actinst = createActivityInstance(activityId, procInst.getId());
                prepareActivitySub(processVO, actVO, ar.procinst, ar.actinst, workTransInstanceId, event, ar.activity);
                if (ar.activity == null) {
                    String msg = "Failed to load the implementor class or create instance: " + actVO.getImplementor();
                    engineLogger.error(processVO.getId(), procInst.getId(), procInst.getMasterRequestId(), msg);
                    ar.startCase = ActivityRuntime.STARTCASE_ERROR_IN_PREPARE;
                } else {
                    ar.startCase = ActivityRuntime.STARTCASE_NORMAL;
                    // notify registered monitors
                    ar.activity.notifyMonitors(InternalLogMessage.ACTIVITY_START);
                }
            } else if (isSyncActivity) {
                ar.actinst = actInsts.get(0);
                if (ar.actinst.getStatusCode() == WorkStatus.STATUS_IN_PROGRESS)
                    ar.actinst = waitForActivityDone(ar.actinst);
                if (ar.actinst.getStatusCode() == WorkStatus.STATUS_WAITING) {
                    if (workTransInstanceId != null && workTransInstanceId > 0L) {
                        getDataAccess().completeTransitionInstance(workTransInstanceId, ar.actinst.getId());
                    }
                    ar.startCase = ActivityRuntime.STARTCASE_SYNCH_WAITING;
                } else if (ar.actinst.getStatusCode() == WorkStatus.STATUS_HOLD) {
                    if (workTransInstanceId != null && workTransInstanceId > 0L) {
                        getDataAccess().completeTransitionInstance(workTransInstanceId, ar.actinst.getId());
                    }
                    ar.startCase = ActivityRuntime.STARTCASE_SYNCH_WAITING;
                } else {    // completed - possible when there are OR conditions
                    if (workTransInstanceId != null && workTransInstanceId > 0L) {
                        getDataAccess().completeTransitionInstance(workTransInstanceId, ar.actinst.getId());
                    }
                    ar.startCase = ActivityRuntime.STARTCASE_SYNCH_COMPLETE;
                }
            } else {
                ActivityInstance onHoldActInst = null;
                for (ActivityInstance actInst : actInsts) {
                    if (actInst.getStatusCode() == WorkStatus.STATUS_HOLD) {
                        onHoldActInst = actInst;
                        break;
                    }
                }
                if (onHoldActInst != null) {
                    if (workTransInstanceId != null && workTransInstanceId > 0L) {
                        getDataAccess().completeTransitionInstance(workTransInstanceId, onHoldActInst.getId());
                    }
                    ar.startCase = ActivityRuntime.STARTCASE_RESUME_WAITING;
                    ar.actinst = onHoldActInst;
                } else {    // WAITING or IN_PROGRESS
                    ar.startCase = ActivityRuntime.STARTCASE_INSTANCE_EXIST;
                }
            }
            return ar;
        } catch (SQLException | MdwException | InterruptedException e) {
            throw new ProcessException(-1, e.getMessage(), e);
        }
    }

    private void prepareActivitySub(Process processVO, Activity actVO,
            ProcessInstance pi, ActivityInstance ai, Long pWorkTransInstId,
            InternalEvent event, BaseActivity activity)
            throws SQLException, MdwException {

        if (logger.isInfoEnabled()) {
            String msg = InternalLogMessage.ACTIVITY_START + " - " + actVO.getName();
            engineLogger.info(pi.getProcessId(), pi.getId(), ai.getActivityId(), ai.getId(), msg);
        }
        if (pWorkTransInstId != null && pWorkTransInstId != 0)
            edao.completeTransitionInstance(pWorkTransInstId, ai.getId());

        if (activity == null) {
            edao.setActivityInstanceStatus(ai, WorkStatus.STATUS_FAILED, "Failed to instantiate activity implementor");
            return;
            // note cannot throw exception here, as when implementor is not defined,
            // the error handling itself will throw exception. We failed the activity outright here.
        }
        Class<?> implClass = activity.getClass();
        TrackingTimer activityTimer = null;
        Tracked t = implClass.getAnnotation(Tracked.class);
        if (t != null) {
            String logTag = EngineLogger.logtag(pi.getProcessId(), pi.getId(), ai.getActivityId(), ai.getId());
            activityTimer = new TrackingTimer(logTag, actVO.getImplementor(), t.value());
        }

        List<VariableInstance> vars;
        if (processVO.isEmbeddedProcess())
            vars = edao.getProcessInstanceVariables(pi.getOwnerId());
        else
            vars = edao.getProcessInstanceVariables(pi.getId());

        event.setWorkInstanceId(ai.getId());

        activity.prepare(actVO, pi, ai, vars, pWorkTransInstId,
                event.getCompletionCode(), activityTimer, new ProcessExecutor(this));
        // prepare Activity to update SLA Instance
        // now moved to EventWaitActivity
    }

    private void removeActivitySLA(ActivityInstance ai, ProcessInstance procInst) {
        Process procdef = getProcessDefinition(procInst);
        Activity actVO = procdef.getActivity(ai.getActivityId());
        String sla = actVO==null?null:actVO.getAttribute(WorkAttributeConstant.SLA);
        if (sla != null && !"0".equals(sla)) {
            ScheduledEventQueue eventQueue = ScheduledEventQueue.getSingleton();
            try {
                eventQueue.unscheduleEvent(ScheduledEvent.INTERNAL_EVENT_PREFIX+ai.getId());
            } catch (Exception e) {
                if (logger.isDebugEnabled()) logger.debugException("Failed to unschedule SLA", e);
            }
        }
    }

    private void failActivityInstance(ActivityInstance ai, String statusMsg,
            ProcessInstance procinst, String logtag, String abbrStatusMsg)
            throws DataAccessException, SQLException {
        edao.setActivityInstanceStatus(ai, WorkStatus.STATUS_FAILED, statusMsg);
        removeActivitySLA(ai, procinst);
        engineLogger.info(logtag, procinst.getId(), ai.getId(), InternalLogMessage.ACTIVITY_FAIL + " - " + abbrStatusMsg);
    }

    private void completeActivityInstance(ActivityInstance ai, String compcode,
            ProcessInstance procInst, String logtag)
            throws DataAccessException, SQLException {
        edao.setActivityInstanceStatus(ai, WorkStatus.STATUS_COMPLETED, compcode);
        if (activityTimings)
            edao.setActivityCompletionTime(ai);

        removeActivitySLA(ai, procInst);
        String msg = InternalLogMessage.ACTIVITY_COMPLETE + " - completion code " + (compcode == null ? "null" : ("'" + compcode + "'"));
        engineLogger.info(logtag, procInst.getId(), ai.getId(), msg);
    }

    private void cancelActivityInstance(ActivityInstance ai, String statusMsg,
            ProcessInstance procInst, String logtag)
            throws DataAccessException, SQLException {
        edao.setActivityInstanceStatus(ai, WorkStatus.STATUS_CANCELLED, statusMsg);
        if (activityTimings)
            edao.setActivityCompletionTime(ai);
        removeActivitySLA(ai, procInst);
        engineLogger.info(logtag, procInst.getId(), ai.getId(), InternalLogMessage.ACTIVITY_CANCEL + " - " + statusMsg);
    }

    private void holdActivityInstance(ActivityInstance ai, String logtag)
            throws DataAccessException, SQLException {
        edao.setActivityInstanceStatus(ai, WorkStatus.STATUS_HOLD, null);
        engineLogger.info(logtag, ai.getProcessInstanceId(), ai.getId(), InternalLogMessage.ACTIVITY_HOLD.message);
    }

    private void suspendActivityInstance(BaseActivity activity, ActivityInstance ai, String logtag, String additionalMsg)
            throws DataAccessException, SQLException {
        edao.setActivityInstanceStatus(ai, WorkStatus.STATUS_WAITING, null);
        String msg = InternalLogMessage.ACTIVITY_SUSPEND + (additionalMsg != null ? " - " + additionalMsg : "");
        engineLogger.info(logtag, ai.getProcessInstanceId(), ai.getId(), msg);
        activity.notifyMonitors(InternalLogMessage.ACTIVITY_SUSPEND);
    }

    CompletionCode finishActivityInstance(BaseActivity activity,
            ProcessInstance pi, ActivityInstance ai, InternalEvent event, boolean bypassWait)
            throws ProcessException {
        try {
            if (activity.getTimer() != null)
                activity.getTimer().start("Finish Activity");

            // Step 3  get and parse completion code
            boolean mayNeedWait = !bypassWait && activityNeedsWait(activity);
            String origCompCode = activity.getReturnCode();
            CompletionCode compCode = new CompletionCode();
            compCode.parse(origCompCode);
            Integer actInstStatus = compCode.getActivityInstanceStatus();
            if (actInstStatus == null && mayNeedWait)
                actInstStatus = WorkStatus.STATUS_WAITING;
            String logtag = EngineLogger.logtag(pi.getProcessId(), pi.getId(), ai.getActivityId(), ai.getId());

            // Step 3a if activity not successful
            if (compCode.getEventType().equals(EventType.ERROR)) {
                failActivityInstance(ai, activity.getReturnMessage(),
                        pi, logtag, activity.getReturnMessage());
                if (!AdapterActivity.COMPCODE_AUTO_RETRY.equals(compCode.getCompletionCode())) {
                    DocumentReference docRef = createActivityExceptionDocument(pi, ai, activity, new ActivityException("Activity failed: " + ai.getId()));
                    InternalEvent outmsg = InternalEvent.createActivityErrorMessage(ai.getActivityId(),
                            ai.getId(), pi.getId(), compCode.getCompletionCode(),
                            event.getMasterRequestId(), activity.getReturnMessage(), docRef.getDocumentId());
                    sendInternalEvent(outmsg);
                }
            }

            // Step 3b if activity needs to wait
            else if (mayNeedWait && !actInstStatus.equals(WorkStatus.STATUS_COMPLETED)) {
                if (actInstStatus.equals(WorkStatus.STATUS_HOLD)) {
                    holdActivityInstance(ai, logtag);
                    InternalEvent outmsg = InternalEvent.createActivityNotifyMessage(ai, compCode.getEventType(),
                            pi.getMasterRequestId(), compCode.getCompletionCode());
                    sendInternalEvent(outmsg);
                } else if (actInstStatus.equals(WorkStatus.STATUS_WAITING) &&
                        (compCode.getEventType().equals(EventType.ABORT) || compCode.getEventType().equals(EventType.CORRECT)
                                || compCode.getEventType().equals(EventType.ERROR))) {
                    suspendActivityInstance(activity, ai, logtag, null);
                    InternalEvent outmsg =  InternalEvent.createActivityNotifyMessage(ai, compCode.getEventType(),
                            pi.getMasterRequestId(), compCode.getCompletionCode());
                    sendInternalEvent(outmsg);
                }
                else if (actInstStatus.equals(WorkStatus.STATUS_CANCELLED)) {
                    cancelActivityInstance(ai, compCode.getCompletionCode(), pi,  logtag);
                    InternalEvent outmsg =  InternalEvent.createActivityNotifyMessage(ai, compCode.getEventType(),
                            pi.getMasterRequestId(), compCode.getCompletionCode());
                    sendInternalEvent(outmsg);
                }
                else {
                    suspendActivityInstance(activity, ai, logtag, null);
                }
            }

            // Step 3c. otherwise, activity is successful and complete it
            else {
                completeActivityInstance(ai, origCompCode, pi, logtag);

                // notify registered monitors
                activity.notifyMonitors(InternalLogMessage.ACTIVITY_COMPLETE);

                if (activity instanceof FinishActivity) {
                    String compcode = ((FinishActivity)activity).getProcessCompletionCode();
                    boolean noNotify = ((FinishActivity)activity).doNotNotifyCaller();
                    completeProcessInstance(pi, compcode, noNotify);
                    List<ProcessInstance> subProcessInsts = getDataAccess().getProcessInstances(pi.getProcessId(), OwnerType.MAIN_PROCESS_INSTANCE, pi.getId());
                    for (ProcessInstance subProcessInstanceVO : subProcessInsts) {
                        if (!subProcessInstanceVO.getStatusCode().equals(WorkStatus.STATUS_COMPLETED) &&
                                !subProcessInstanceVO.getStatusCode().equals(WorkStatus.STATUS_CANCELLED))
                            completeProcessInstance(subProcessInstanceVO, compcode, noNotify);
                    }
                } else {
                    InternalEvent outmsg = InternalEvent.createActivityNotifyMessage(ai,
                            compCode.getEventType(), event.getMasterRequestId(), compCode.getCompletionCode());
                    sendInternalEvent(outmsg);
                }
            }
            return compCode;    // not used by asynch engine
        } catch (Exception e) {
            throw new ProcessException(-1, e.getMessage(), e);
        } finally {
            if (activity.getTimer() != null)
                activity.getTimer().stopAndLogTiming();
        }
    }

    ///////////// process finish

    void handleProcessFinish(InternalEvent event) throws ProcessException
    {
        try {
            String ownerType = event.getOwnerType();
            String secondaryOwnerType = event.getSecondaryOwnerType();
            if (!OwnerType.ACTIVITY_INSTANCE.equals(secondaryOwnerType)) {
                // top level processes (non-remote) or ABORT embedded processes
                ProcessInstance pi = edao.getProcessInstance(event.getWorkInstanceId());
                Process subProcVO = getProcessDefinition(pi);
                if (pi.isEmbedded()) {
                    subProcVO.getSubProcess(event.getWorkId());
                    String embeddedProcType = subProcVO.getAttribute(WorkAttributeConstant.EMBEDDED_PROCESS_TYPE);
                    if (ProcessVisibilityConstant.EMBEDDED_ABORT_PROCESS.equals(embeddedProcType)) {
                        Long parentProcInstId = event.getOwnerId();
                        pi = edao.getProcessInstance(parentProcInstId);
                        cancelProcessInstanceTree(pi);
                        engineLogger.info(pi.getProcessId(), pi.getId(), pi.getMasterRequestId(), "Process cancelled");
                        InternalEvent procFinishMsg = InternalEvent.createProcessFinishMessage(pi);
                        if (OwnerType.ACTIVITY_INSTANCE.equals(pi.getSecondaryOwner())) {
                            procFinishMsg.setSecondaryOwnerType(pi.getSecondaryOwner());
                            procFinishMsg.setSecondaryOwnerId(pi.getSecondaryOwnerId());
                        }
                        sendInternalEvent(procFinishMsg);
                    }
                }
            } else if (ownerType.equals(OwnerType.PROCESS_INSTANCE)
                    || ownerType.equals(OwnerType.MAIN_PROCESS_INSTANCE)
                    || ownerType.equals(OwnerType.ERROR)) {
                // local process call or call to error/correction/delay handler
                Long activityInstId = event.getSecondaryOwnerId();
                ActivityInstance actInst = edao.getActivityInstance(activityInstId);
                ProcessInstance procInst = edao.getProcessInstance(actInst.getProcessInstanceId());
                BaseActivity cntrActivity = prepareActivityForResume(event,procInst, actInst);
                if (cntrActivity!=null) {
                    resumeProcessInstanceForSecondaryOwner(event, cntrActivity);
                }    // else the process is completed/cancelled
            }
        } catch (Exception e) {
            throw new ProcessException(-1, e.getMessage(), e);
        }
    }

    private void handleResumeOnHold(GeneralActivity cntrActivity, ActivityInstance actInst,
            ProcessInstance procInst)
            throws DataAccessException, MdwException {
        try {
            InternalEvent event = InternalEvent.createActivityNotifyMessage(actInst,
                    EventType.RESUME, procInst.getMasterRequestId(), actInst.getStatusCode()==WorkStatus.STATUS_COMPLETED? "Completed" : null);
            boolean finished = ((SuspendableActivity)cntrActivity).resumeWaiting(event);
            this.resumeActivityFinishSub(actInst, (BaseActivity)cntrActivity, procInst,
                    finished, true);
        } catch (Exception e) {
            logger.severeException("Resume failed", e);
            String statusMsg = "activity failed during resume";
            try {
                String logtag = EngineLogger.logtag(procInst.getProcessId(), procInst.getId(), actInst.getActivityId(), actInst.getId());
                failActivityInstance(actInst, statusMsg, procInst, logtag, statusMsg);
            } catch (SQLException e1) {
                throw new DataAccessException(-1, e1.getMessage(), e1);
            }
            DocumentReference docRef = createActivityExceptionDocument(procInst, actInst, (BaseActivity)cntrActivity, e);
            InternalEvent event = InternalEvent.createActivityErrorMessage(
                    actInst.getActivityId(), actInst.getId(), procInst.getId(), null,
                    procInst.getMasterRequestId(), statusMsg, docRef.getDocumentId());
            this.sendInternalEvent(event);
        }
    }

    /**
     * Resumes the process instance for the secondary owner
     */
    private void resumeProcessInstanceForSecondaryOwner(InternalEvent event,
            BaseActivity cntrActivity) throws Exception {
        Long actInstId = event.getSecondaryOwnerId();
        ActivityInstance actInst = edao.getActivityInstance(actInstId);
        String masterRequestId = event.getMasterRequestId();
        Long parentInstId = actInst.getProcessInstanceId();
        ProcessInstance parentInst = edao.getProcessInstance(parentInstId);
        String logtag = EngineLogger.logtag(parentInst.getProcessId(), parentInstId, actInst.getActivityId(), actInstId);
        boolean isEmbeddedProcess;
        if (event.getOwnerType().equals(OwnerType.MAIN_PROCESS_INSTANCE))
            isEmbeddedProcess = true;
        else if (event.getOwnerType().equals(OwnerType.PROCESS_INSTANCE)) {
            try {
                Process subprocdef = ProcessCache.getProcess(event.getWorkId());
                isEmbeddedProcess = subprocdef.isEmbeddedProcess();
            } catch (Exception e1) {
                // can happen when the subprocess is remote
                String msg = "subprocess definition cannot be found - treat it as a remote process - id " + event.getWorkId();
                engineLogger.info(logtag, actInst.getProcessInstanceId(), actInstId, msg);
                isEmbeddedProcess = false;
            }
        } else isEmbeddedProcess = false;    // including the case the subprocess is remote
        String compCode = event.getCompletionCode();
        if (isEmbeddedProcess || event.getOwnerType().equals(OwnerType.ERROR)) {
            // mark parent process instance in progress
            edao.setProcessInstanceStatus(parentInst.getId(), WorkStatus.STATUS_IN_PROGRESS);
            String msg = "Activity resumed from embedded subprocess, which returns completion code " + compCode;
            engineLogger.info(logtag, actInst.getProcessInstanceId(), actInstId, msg);
            CompletionCode parsedCompCode = new CompletionCode();
            parsedCompCode.parse(event.getCompletionCode());
            Transition outgoingWorkTransVO = null;
            if (compCode==null || parsedCompCode.getEventType().equals(EventType.RESUME)) {        // default behavior
                if (actInst.getStatusCode()==WorkStatus.STATUS_HOLD ||
                        actInst.getStatusCode()==WorkStatus.STATUS_COMPLETED) {
                    handleResumeOnHold(cntrActivity, actInst, parentInst);
                } else if (actInst.getStatusCode()==WorkStatus.STATUS_FAILED) {
                    completeActivityInstance(actInst, compCode, parentInst, logtag);
                    // notify registered monitors
                    cntrActivity.notifyMonitors(InternalLogMessage.ACTIVITY_FAIL);

                    InternalEvent jmsmsg = InternalEvent.createActivityNotifyMessage(actInst,
                            EventType.FINISH, masterRequestId, null);
                    sendInternalEvent(jmsmsg);
                }
            } else if (parsedCompCode.getEventType().equals(EventType.ABORT)) {    // TaskAction.ABORT and TaskAction.CANCEL
                String comment = actInst.getMessage() + "  \nException handler returns " + compCode;
                if (actInst.getStatusCode() != WorkStatus.STATUS_COMPLETED) {
                    cancelActivityInstance(actInst, comment, parentInst, logtag);
                }
                if (parsedCompCode.getCompletionCode()!=null && parsedCompCode.getCompletionCode().startsWith("process"))    {// TaskAction.ABORT
                    InternalEvent outgoingMsg = InternalEvent.createActivityNotifyMessage(actInst,
                            EventType.ABORT, parentInst.getMasterRequestId(), null);
                    sendInternalEvent(outgoingMsg);
                }
            } else if (parsedCompCode.getEventType().equals(EventType.START)) {        // TaskAction.RETRY
                String comment = actInst.getMessage() + "  \nException handler returns " + compCode;
                if (actInst.getStatusCode() != WorkStatus.STATUS_COMPLETED) {
                    cancelActivityInstance(actInst, comment, parentInst, logtag);
                }
                retryActivity(parentInst, actInst.getActivityId(), null, masterRequestId);
            } else {    // event type must be FINISH
                if (parsedCompCode.getCompletionCode() != null)
                    outgoingWorkTransVO = findTaskActionWorkTransition(parentInst, actInst, parsedCompCode.getCompletionCode());
                if (actInst.getStatusCode() != WorkStatus.STATUS_COMPLETED && actInst.getStatusCode() != WorkStatus.STATUS_CANCELLED) {
                    completeActivityInstance(actInst, compCode, parentInst, logtag);
                    cntrActivity.notifyMonitors(InternalLogMessage.ACTIVITY_COMPLETE);
                    InternalEvent jmsmsg;
                    int delay = 0;
                    if (outgoingWorkTransVO != null) {
                        // is custom action (RESUME), transition accordingly
                        TransitionInstance workTransInst = createTransitionInstance(outgoingWorkTransVO, parentInstId);
                        jmsmsg = InternalEvent.createActivityStartMessage(
                                outgoingWorkTransVO.getToId(), parentInstId,
                                workTransInst.getTransitionInstanceID(), masterRequestId,
                                outgoingWorkTransVO.getLabel());
                        delay = getTransitionDelay(outgoingWorkTransVO, parentInst);
                    } else {
                        jmsmsg = InternalEvent.createActivityNotifyMessage(actInst,
                                EventType.FINISH, masterRequestId, null);
                    }
                    if (delay > 0) {
                        String msgid = ScheduledEvent.INTERNAL_EVENT_PREFIX + parentInstId
                                + "start" + outgoingWorkTransVO.getToId();
                        sendDelayedInternalEvent(jmsmsg, delay, msgid, false);
                    } else sendInternalEvent(jmsmsg);
                }
            }
        } else {    // must be InvokeProcessActivity
            if (actInst.getStatusCode() == WorkStatus.STATUS_WAITING || actInst.getStatusCode() == WorkStatus.STATUS_HOLD) {
                boolean isSynchronized = ((InvokeProcessActivity)cntrActivity).resume(event);
                if (isSynchronized) {   // all subprocess instances terminated
                    // mark parent process instance in progress
                    edao.setProcessInstanceStatus(parentInst.getId(), WorkStatus.STATUS_IN_PROGRESS);
                    // complete the activity and send activity FINISH message
                    CompletionCode parsedCompCode = new CompletionCode();
                    parsedCompCode.parse(event.getCompletionCode());
                    if (parsedCompCode.getEventType().equals(EventType.ABORT)) {
                        cancelActivityInstance(actInst, "Subprocess is cancelled", parentInst, logtag);
                    } else {
                        completeActivityInstance(actInst, compCode, parentInst, logtag);
                        cntrActivity.notifyMonitors(InternalLogMessage.ACTIVITY_COMPLETE);
                    }
                    InternalEvent jmsmsg = InternalEvent.createActivityNotifyMessage(actInst,
                            EventType.FINISH, masterRequestId, compCode);
                    sendInternalEvent(jmsmsg);
                }  else {
                    // multiple instances and not all terminated - do nothing
                    String msg = "Activity continue suspend - not all child processes have completed";
                    engineLogger.info(logtag, actInst.getProcessInstanceId(), actInstId, msg);
                }
            } else {  // status is COMPLETED or others
                // do nothing - asynchronous subprocess call
                String msg = "Activity not waiting for subprocess - asynchronous subprocess call";
                engineLogger.info(logtag, actInst.getProcessInstanceId(), actInstId, msg);
            }
        }
    }

    private void completeProcessInstance(ProcessInstance procInst) throws Exception {
        edao.setProcessCompletionTime(procInst);
        edao.setProcessInstanceStatus(procInst.getId(), WorkStatus.STATUS_COMPLETED);
        if (!inService) {
            edao.removeEventWaitForProcessInstance(procInst.getId());
            this.cancelTasksOfProcessInstance(procInst);
        }
    }

    private void completeProcessInstance(ProcessInstance processInst, String completionCode, boolean noNotify)
            throws Exception {

        Process process = getProcessDefinition(processInst);
        InternalEvent retMsg = InternalEvent.createProcessFinishMessage(processInst);

        if (OwnerType.ACTIVITY_INSTANCE.equals(processInst.getSecondaryOwner())) {
            retMsg.setSecondaryOwnerType(processInst.getSecondaryOwner());
            retMsg.setSecondaryOwnerId(processInst.getSecondaryOwnerId());
        }

        if (completionCode==null) completionCode = processInst.getCompletionCode();
        if (completionCode!=null) retMsg.setCompletionCode(completionCode);

        boolean isCancelled = false;
        if (completionCode==null) {
            completeProcessInstance(processInst);
        } else if (process.isEmbeddedProcess()) {
            completeProcessInstance(processInst);
            retMsg.setCompletionCode(completionCode);
        } else {
            CompletionCode parsedCompCode = new CompletionCode();
            parsedCompCode.parse(completionCode);
            if (parsedCompCode.getEventType().equals(EventType.ABORT)) {
                this.cancelProcessInstanceTree(processInst);
                isCancelled = true;
            } else if (parsedCompCode.getEventType().equals(EventType.FINISH)) {
                completeProcessInstance(processInst);
                if (parsedCompCode.getCompletionCode()!=null) {
                    completionCode = parsedCompCode.getCompletionCode();
                    retMsg.setCompletionCode(completionCode);
                } else completionCode = null;
            } else {
                completeProcessInstance(processInst);
                retMsg.setCompletionCode(completionCode);
            }
        }
        if (!noNotify)
            sendInternalEvent(retMsg);
        String msg = (isCancelled ? InternalLogMessage.PROCESS_CANCEL.message : InternalLogMessage.PROCESS_COMPLETE.message) + " - " + process.getQualifiedName()
                + (isCancelled ? "" : completionCode == null ? " completion code is null" : (" completion code = " + completionCode));
        engineLogger.info(process.getId(), processInst.getId(), processInst.getMasterRequestId(), msg);
        notifyMonitors(processInst, InternalLogMessage.PROCESS_COMPLETE);
    }

    /**
     * Look up the appropriate work transition for an embedded exception handling subprocess.
     * @param parentInstance the parent process
     * @param activityInstance the activity in the main process
     * @param taskAction the selected task action
     * @return the matching work transition, if found
     */
    private Transition findTaskActionWorkTransition(ProcessInstance parentInstance,
            ActivityInstance activityInstance, String taskAction) {
        if (taskAction == null)
            return null;

        Process processVO = getProcessDefinition(parentInstance);
        Transition workTransVO = processVO.getTransition(activityInstance.getActivityId(), EventType.RESUME, taskAction);
        if (workTransVO == null) {
            // try upper case
            workTransVO = processVO.getTransition(activityInstance.getActivityId(), EventType.RESUME, taskAction.toUpperCase());
        }
        if (workTransVO == null) {
            workTransVO = processVO.getTransition(activityInstance.getActivityId(), EventType.FINISH, taskAction);
        }
        return workTransVO;
    }

    private void retryActivity(ProcessInstance procInst, Long actId,
            String completionCode, String masterRequestId)
            throws DataAccessException, SQLException, MdwException {
        // make sure any other activity instances are closed
        List<ActivityInstance> activityInstances = edao.getActivityInstances(actId, procInst.getId(),
                true, false);
        for (ActivityInstance actInst :  activityInstances) {
            if (actInst.getStatusCode() == WorkStatus.STATUS_IN_PROGRESS
                    || actInst.getStatusCode()== WorkStatus.STATUS_PENDING_PROCESS) {
                String logtag = EngineLogger.logtag(procInst.getProcessId(), procInst.getId(), actId, actInst.getId());
                failActivityInstance(actInst, "Retry Activity Action", procInst, logtag, "Retry Activity Action");
            }
        }
        // start activity again
        InternalEvent event = InternalEvent.createActivityStartMessage(actId,
                procInst.getId(), null, masterRequestId, EventType.EVENTNAME_START);
        sendInternalEvent(event);
    }

    /////////////// activity resume

    private boolean validateProcessInstance(ProcessInstance processInst) {
        Integer status = processInst.getStatusCode();
        if (WorkStatus.STATUS_CANCELLED.equals(status)) {
            logger.info("ProcessInstance has been cancelled. ProcessInstanceId = " + processInst.getId());
            return false;
        } else if (WorkStatus.STATUS_COMPLETED.equals(status)) {
            logger.info("ProcessInstance has been completed. ProcessInstanceId = " + processInst.getId());
            return false;
        } else return true;
    }

    private BaseActivity prepareActivityForResume(InternalEvent event, ProcessInstance procInst, ActivityInstance actInst)
    {
        Long actId = actInst.getActivityId();
        Long procInstId = actInst.getProcessInstanceId();

        if (!validateProcessInstance(procInst)) {
            String msg = "Activity would resume, but process is no longer alive";
            engineLogger.info(procInst.getProcessId(), procInstId, actId, actInst.getId(), msg);
            return null;
        }
        String msg = "Activity to resume";
        engineLogger.info(procInst.getProcessId(), procInstId, actId, actInst.getId(), msg);

        Process processVO = getProcessDefinition(procInst);
        Activity actVO = processVO.getActivity(actId);

        TrackingTimer activityTimer = null;
        try {
            // use design-time package
            Package pkg = PackageCache.getPackage(getMainProcessDefinition(procInst).getPackageName());
            BaseActivity cntrActivity = (BaseActivity)getActivityInstance(pkg, actVO.getImplementor());
            Tracked t = cntrActivity.getClass().getAnnotation(Tracked.class);
            if (t != null) {
                String logTag = EngineLogger.logtag(procInst.getProcessId(), procInst.getId(), actId, actInst.getId());
                activityTimer = new TrackingTimer(logTag, cntrActivity.getClass().getName(), t.value());
                activityTimer.start("Prepare Activity for Resume");
            }
            List<VariableInstance> vars = processVO.isEmbeddedProcess()?
                    edao.getProcessInstanceVariables(procInst.getOwnerId()):
                    edao.getProcessInstanceVariables(procInstId);
            // procInst.setVariables(vars);     set inside edac method
            Long workTransitionInstId = event.getTransitionInstanceId();
            cntrActivity.prepare(actVO, procInst, actInst, vars, workTransitionInstId,
                    event.getCompletionCode(), activityTimer, new ProcessExecutor(this));
            return cntrActivity;
        } catch (Exception e) {
            engineLogger.error(procInst.getProcessId(), procInst.getId(), actInst.getActivityId(), actInst.getId(),
                    "Unable to instantiate implementer " + actVO.getImplementor(), e);
            return null;
        }
        finally {
            if (activityTimer != null) {
                activityTimer.stopAndLogTiming();
            }
        }
    }

    private boolean isProcessInstanceResumable(ProcessInstance pInstance) {
        int statusCd = pInstance.getStatusCode();
        if (statusCd == WorkStatus.STATUS_COMPLETED) {
            return false;
        } else return statusCd != WorkStatus.STATUS_CANCELLED;
    }

    ActivityRuntime resumeActivityPrepare(ProcessInstance procInst,
            InternalEvent event, boolean resumeOnHold)
            throws ProcessException, DataAccessException {
        Long actInstId = event.getWorkInstanceId();
        try {
            ActivityRuntime ar = new ActivityRuntime();
            ar.startCase = ActivityRuntime.RESUMECASE_NORMAL;
            ar.actinst = edao.getActivityInstance(actInstId);
            ar.procinst = procInst;
            if (!this.isProcessInstanceResumable(ar.procinst)) {
                ar.startCase = ActivityRuntime.RESUMECASE_PROCESS_TERMINATED;
                String msg = "Cannot resume activity instance as the process is completed/canceled";
                engineLogger.info(ar.procinst.getProcessId(), ar.procinst.getId(), ar.actinst.getActivityId(), actInstId, msg);
                return ar;
            }
            if (!resumeOnHold && ar.actinst.getStatusCode()!= WorkStatus.STATUS_WAITING) {
                String msg = "Cannot resume activity instance as it is not waiting any more";
                engineLogger.info(ar.procinst.getProcessId(), ar.procinst.getId(), ar.actinst.getActivityId(), actInstId, msg);
                ar.startCase = ActivityRuntime.RESUMECASE_ACTIVITY_NOT_WAITING;
                return ar;
            }
            ar.activity = prepareActivityForResume(event, ar.procinst, ar.actinst);
            if (resumeOnHold)
                event.setEventType(EventType.RESUME);
            else
                event.setEventType(EventType.FINISH);
            return ar;
        } catch (SQLException e) {
            throw new ProcessException(-1, e.getMessage(), e);
        }
    }

    private void resumeActivityFinishSub(ActivityInstance actinst, BaseActivity activity, ProcessInstance procinst,
            boolean finished, boolean resumeOnHold)  throws SQLException, MdwException {
        String logtag = EngineLogger.logtag(procinst.getProcessId(),procinst.getId(), actinst.getActivityId(),actinst.getId());
        if (finished) {
            CompletionCode completionCode = new CompletionCode();
            completionCode.parse(activity.getReturnCode());
            if (WorkStatus.STATUS_HOLD.equals(completionCode.getActivityInstanceStatus())) {
                holdActivityInstance(actinst, logtag);
            } else if (WorkStatus.STATUS_WAITING.equals(completionCode.getActivityInstanceStatus())) {
                suspendActivityInstance(activity, actinst, logtag, "continue suspend");
            } else if (WorkStatus.STATUS_CANCELLED.equals(completionCode.getActivityInstanceStatus())) {
                cancelActivityInstance(actinst, "Cancelled upon resume", procinst, logtag);
            } else if (WorkStatus.STATUS_FAILED.equals(completionCode.getActivityInstanceStatus())) {
                failActivityInstance(actinst, "Failed upon resume", procinst, logtag, activity.getReturnMessage());
            } else {    // status is null or Completed
                completeActivityInstance(actinst, completionCode.toString(), procinst, logtag);
                // notify registered monitors
                activity.notifyMonitors(InternalLogMessage.ACTIVITY_COMPLETE);
            }
            InternalEvent event = InternalEvent.createActivityNotifyMessage(actinst,
                    completionCode.getEventType(), procinst.getMasterRequestId(),
                    completionCode.getCompletionCode());
            sendInternalEvent(event);
        } else {
            if (resumeOnHold) {
                suspendActivityInstance(activity, actinst, logtag, "resume waiting after hold");
            } else {
                engineLogger.info(logtag, actinst.getProcessInstanceId(), actinst.getId(), "continue suspend");
            }
        }
    }

    void resumeActivityFinish(ActivityRuntime ar, boolean finished, InternalEvent event, boolean resumeOnHold)
            throws ProcessException {
        try {
            if (ar.activity.getTimer() != null)
                ar.activity.getTimer().start("Resume Activity Finish");
            this.resumeActivityFinishSub(ar.actinst, ar.activity, ar.procinst,
                    finished, resumeOnHold);
        } catch (SQLException | MdwException e) {
            throw new ProcessException(-1, e.getMessage(), e);
        } finally {
            if (ar.activity.getTimer() != null)
                ar.activity.getTimer().stopAndLogTiming();
        }
    }

    boolean resumeActivityExecute(ActivityRuntime ar, InternalEvent event, boolean resumeOnHold) throws ActivityException {
        boolean finished;
        try {
            if (ar.activity.getTimer() != null)
                ar.activity.getTimer().start("Resume Activity");
            if (resumeOnHold)
                finished = ((SuspendableActivity)ar.activity).resumeWaiting(event);
            else
                finished = ((SuspendableActivity)ar.activity).resume(event);
        }
        finally {
            if (ar.activity.getTimer() != null)
                ar.activity.getTimer().stopAndLogTiming();
        }
        return finished;
    }

    Map<String, String> getOutputParameters(Long procInstId, Long procId) throws IOException, SQLException, DataAccessException {
        Process subprocDef = ProcessCache.getProcess(procId);
        Map<String, String> params = new HashMap<>();
        boolean passDocContent = (isInService() && getDataAccess().getPerformanceLevel() >= 5) || getDataAccess().getPerformanceLevel() >= 9 ;  // DHO  (if not serviceProc then lvl9)
        for (Variable var : subprocDef.getVariables()) {
            if (var.getVariableCategory() == Variable.CAT_OUTPUT
                    || var.getVariableCategory() == Variable.CAT_INOUT) {
                VariableInstance vio = getDataAccess()
                        .getVariableInstance(procInstId,
                                var.getName());
                if (vio != null) {
                    if (passDocContent && vio.isDocument()) {
                        Document docvo = getDocument((DocumentReference)vio.getData(), false);
                        if (docvo != null)
                            params.put(var.getName(), docvo.getContent(getPackage(subprocDef)));
                    }
                    else {
                        params.put(var.getName(), vio.getStringValue());
                    }
                }
            }
        }
        return params;
    }

    void resumeActivityException(
            ProcessInstance procInst,
            Long actInstId, BaseActivity activity, Throwable cause) {
        String compCode = null;
        try {
            String statusMsg = buildStatusMessage(cause);
            ActivityInstance actInst = edao.getActivityInstance(actInstId);
            String logtag = EngineLogger.logtag(procInst.getProcessId(), procInst.getId(), actInst.getActivityId(), actInst.getId());
            failActivityInstance(actInst, statusMsg, procInst, logtag, "Exception in resume");
            if (activity == null || !AdapterActivity.COMPCODE_AUTO_RETRY.equals(activity.getReturnCode())) {
                Throwable th = cause == null ? new ActivityException("Resume activity: " + actInstId) : cause;
                DocumentReference docRef = createActivityExceptionDocument(procInst, actInst, activity, th);
                InternalEvent outgoingMsg = InternalEvent.createActivityErrorMessage(
                        actInst.getActivityId(), actInst.getId(),
                        procInst.getId(), compCode, procInst.getMasterRequestId(),
                        statusMsg, docRef.getDocumentId());
                sendInternalEvent(outgoingMsg);
            }
        }
        catch (Exception e) {
            engineLogger.error(procInst.getProcessId(), procInst.getId(), activity.getActivityId(), actInstId,
                    "**Failed in handleResumeException**", e);
        }
    }

    //////// handle process abort

    /**
     * Abort a single process instance by process instance ID,
     * or abort potentially multiple (but typically one) process instances
     * by process ID and owner ID.
     */
    void abortProcessInstance(InternalEvent event)
            throws ProcessException {
        Long processId = event.getWorkId();
        String processOwner = event.getOwnerType();
        Long processOwnerId = event.getOwnerId();
        Long processInstId = event.getWorkInstanceId();
        try {
            if (processInstId!=null && processInstId !=0L) {
                ProcessInstance pi = edao.getProcessInstance(processInstId);
                cancelProcessInstanceTree(pi);
                engineLogger.info(pi.getProcessId(), pi.getId(), pi.getMasterRequestId(), "Process cancelled");
            } else {
                List<ProcessInstance> coll = edao.getProcessInstances(processId, processOwner, processOwnerId);
                if (coll == null || coll.isEmpty()) {
                    logger.info("No Process Instances for the Process and Owner");
                    return;
                }
                for (ProcessInstance pi : coll) {
                    // there really should have only one
                    cancelProcessInstanceTree(pi);
                }
            }
        }
        catch (Exception ex) {
            logger.severeException(ex.getMessage(), ex);
            throw new ProcessException(ex.getMessage());
        }
    }

    /**
     * Cancels the process instance as well as all descendant process instances.
     * Deregisters associated event wait instances.
     */
    private void cancelProcessInstanceTree(ProcessInstance pi)
            throws Exception {
        if (pi.getStatusCode().equals(WorkStatus.STATUS_COMPLETED) ||
                pi.getStatusCode().equals(WorkStatus.STATUS_CANCELLED)) {
            throw new ProcessException("ProcessInstance is not in a cancellable state");
        }
        List<ProcessInstance> childInstances = edao.getChildProcessInstances(pi.getId());
        for (ProcessInstance child : childInstances) {
            if (!child.getStatusCode().equals(WorkStatus.STATUS_COMPLETED)
                    && !child.getStatusCode().equals(WorkStatus.STATUS_CANCELLED)) {
                this.cancelProcessInstanceTree(child);
            } else {
                logger.info("Descendent ProcessInstance in not in a cancellable state. ProcessInstanceId="
                        + child.getId());
            }
        }
        this.cancelProcessInstance(pi);
    }

    /**
     * Cancels a single process instance.
     * It cancels all active transition instances, all event wait instances,
     * and sets the process instance into canceled status.
     *
     * The method does not cancel task instances
     *
     * @param pProcessInst process instance.
     * @return new WorkInstance
     */
    private void cancelProcessInstance(ProcessInstance pProcessInst)
            throws Exception {
        edao.cancelTransitionInstances(pProcessInst.getId(),
                "ProcessInstance has been cancelled.", null);
        edao.setProcessInstanceStatus(pProcessInst.getId(), WorkStatus.STATUS_CANCELLED);
        edao.removeEventWaitForProcessInstance(pProcessInst.getId());
        this.cancelErrorHandlers(pProcessInst);
        this.cancelExceptionHandlers(pProcessInst);
        this.cancelTasksOfProcessInstance(pProcessInst);
    }

    private void cancelErrorHandlers(ProcessInstance procInst) throws Exception {
        Query query = new Query();
        procInst = ServiceLocator.getWorkflowServices().getProcess(procInst.getId());
        for (ActivityInstance activity : procInst.getActivities()) {
            query.setFilter("owner", "ERROR");
            query.setFilter("secondaryOwner", "ACTIVITY_INSTANCE");
            query.setFilter("secondaryOwnerId", activity.getId());
            query.setSort("process_instance_id");
            query.setDescending(true);
            List<ProcessInstance> processInstanceList = ServiceLocator.getWorkflowServices().getProcesses(query).getProcesses();
            for (ProcessInstance pi : processInstanceList) {
                cancelProcessInstance(pi);
            }
        }
    }

    private void cancelExceptionHandlers(ProcessInstance procInst) throws Exception {
        Query query = new Query();
        query.setFilter("owner", "MAIN_PROCESS_INSTANCE");
        query.setFilter("ownerId", procInst.getId());
        query.setFilter("secondaryOwner", "ACTIVITY_INSTANCE");
        query.setSort("process_instance_id");
        query.setDescending(true);
        List<ProcessInstance> processInstanceList = ServiceLocator.getWorkflowServices().getProcesses(query).getProcesses();
        for (ProcessInstance pi : processInstanceList) {
            cancelProcessInstance(pi);
        }
    }

    /////////////////////// other

    private void cancelTasksOfProcessInstance(ProcessInstance procInst)
            throws NamingException, JMSException, SQLException, ServiceLocatorException, MdwException {
        List<ProcessInstance> processInstanceList =
                edao.getChildProcessInstances(procInst.getId());
        List<Long> procInstIds = new ArrayList<>();
        procInstIds.add(procInst.getId());
        for (ProcessInstance pi : processInstanceList) {
            Process pidef = getProcessDefinition(pi);
            if (pidef.isEmbeddedProcess())
                procInstIds.add(pi.getId());
        }
        TaskServices taskServices = ServiceLocator.getTaskServices();
        for (Long procInstId : procInstIds) {
            taskServices.cancelTaskInstancesForProcess(procInstId);
        }
    }

    EventWaitInstance createEventWaitInstance(Long procInstId, Long actInstId, String pEventName, String compCode,
            boolean pRecurring, boolean notifyIfArrived) throws ProcessException {
        return createEventWaitInstance(procInstId, actInstId, pEventName, compCode, pRecurring, notifyIfArrived, false);
    }

    EventWaitInstance createEventWaitInstance(Long procInstId, Long actInstId, String pEventName, String compCode,
            boolean pRecurring, boolean notifyIfArrived, boolean reregister) throws ProcessException {
        try {
            String FINISH = EventType.getEventTypeName(EventType.FINISH);
            if (compCode==null||compCode.length()==0) compCode = FINISH;
            EventWaitInstance ret = null;
            Long documentId;

            documentId = edao.recordEventWait(pEventName,
                    !pRecurring,
                    3600,
                    actInstId, compCode);

            String msg = "registered event wait event='" + pEventName + "' actInst=" + actInstId + (pRecurring?" as recurring":" as broadcast-waiting");
            engineLogger.info(procInstId, actInstId, msg);

            if (documentId!=null && !reregister) {
                msg = (notifyIfArrived ? "notify" : "return") + " event before registration: event='" + pEventName + "' actInst=" + actInstId;
                engineLogger.info(procInstId, actInstId, msg);
                if (notifyIfArrived) {
                    if (compCode.equals(FINISH)) compCode = null;
                    ActivityInstance actInst = edao.getActivityInstance(actInstId);
                    resumeActivityInstance(actInst, compCode, documentId, null, 0);
                    edao.removeEventWaitForActivityInstance(actInstId, "activity notified");
                } else {
                    edao.removeEventWaitForActivityInstance(actInstId, "activity to notify is returned");
                }
                ret = new EventWaitInstance();
                ret.setMessageDocumentId(documentId);
                ret.setCompletionCode(compCode);
                Document docvo = edao.getDocument(documentId, true);
                edao.updateDocumentInfo(docvo);
            }
            return ret;
        } catch (MdwException | SQLException e) {
            throw new ProcessException(-1, e.getMessage(), e);
        }

    }

    /**
     * Method that creates the event log based on the passed in params
     */
    EventWaitInstance createEventWaitInstances(Long procInstId, Long actInstId, String[] eventNames,
            String[] wakeUpEventTypes, boolean[] eventOccurances, boolean notifyIfArrived, boolean reregister)
            throws ProcessException {
        try {
            EventWaitInstance ret = null;
            Long documentId = null;
            String compCode = null;
            int i;
            for (i = 0; i < eventNames.length; i++) {
                compCode = wakeUpEventTypes[i];
                documentId = edao.recordEventWait(eventNames[i],
                        !eventOccurances[i],
                        3600,       // TODO set this value in Studio
                        actInstId, wakeUpEventTypes[i]);
                String msg = "registered event wait event='" + eventNames[i] + "' actInst=" + actInstId +
                        (eventOccurances[i] ? " as recurring" : " as broadcast-waiting");
                engineLogger.info(procInstId, actInstId, msg);

                if (documentId != null && !reregister)
                    break;
            }
            if (documentId != null && !reregister) {
                String msg = (notifyIfArrived ? "notify" : "return") + " event before registration: event='" +
                        eventNames[i] + "' actInst=" + actInstId;
                engineLogger.info(procInstId, actInstId, msg);
                if (compCode != null && compCode.length() == 0)
                    compCode = null;
                if (notifyIfArrived) {
                    ActivityInstance actInst = edao.getActivityInstance(actInstId);
                    resumeActivityInstance(actInst, compCode, documentId, null, 0);
                    edao.removeEventWaitForActivityInstance(actInstId, "activity notified");
                } else {
                    edao.removeEventWaitForActivityInstance(actInstId, "activity to notify is returned");
                }
                ret = new EventWaitInstance();
                ret.setMessageDocumentId(documentId);
                ret.setCompletionCode(compCode);
                Document docvo = edao.getDocument(documentId, true);
                edao.updateDocumentInfo(docvo);
            }
            return ret;
        } catch (SQLException | MdwException e) {
            throw new ProcessException(-1, e.getMessage(), e);
        }
    }

    Integer notifyProcess(String eventName, Long docId, String message, int delay) throws EventException, SQLException {
        List<EventWaitInstance> waiters;

        waiters = edao.recordEventArrive(eventName, docId);

        if (waiters != null && !waiters.isEmpty()) {
            boolean hasFailures = false;
            try {
                for (EventWaitInstance inst : waiters) {
                    String compCode = inst.getCompletionCode();
                    if (compCode != null && compCode.length() == 0)
                        compCode = null;

                    ActivityInstance actInst = edao.getActivityInstance(inst.getActivityInstanceId());
                    String msg = "notify event after registration: event='" + eventName + "' actInst=" + inst.getActivityInstanceId();
                    engineLogger.info(actInst.getProcessInstanceId(), actInst.getId(), msg);

                    if (actInst.getStatusCode() == WorkStatus.STATUS_IN_PROGRESS) {
                        // assuming it is a service process waiting for message
                        JSONObject json = new JsonObject();
                        json.put("ACTION", "NOTIFY");
                        json.put("CORRELATION_ID", eventName);
                        json.put("MESSAGE", message);
                        internalMessenger.broadcastMessage(json.toString());
                    } else {
                        resumeActivityInstance(actInst, compCode, docId, message, delay);
                    }
                    // deregister wait instances
                    edao.removeEventWaitForActivityInstance(inst.getActivityInstanceId(), "activity notified");
                    if (docId != null && docId > 0) {
                        Document docvo = edao.getDocument(docId, true);
                        edao.updateDocumentInfo(docvo);
                    }
                }
            } catch (Exception ex) {
                logger.severeException(ex.getMessage(), ex);
                throw new EventException(ex.getMessage(), ex);
            }
            if (hasFailures)
                return EventInstance.RESUME_STATUS_PARTIAL_SUCCESS;
            else
                return EventInstance.RESUME_STATUS_SUCCESS;
        } else {
            return EventInstance.RESUME_STATUS_NO_WAITERS;
        }
    }

    private boolean isProcessInstanceProgressable(ProcessInstance pInstance) {

        int statusCd = pInstance.getStatusCode();
        if (statusCd == WorkStatus.STATUS_COMPLETED) {
            return false;
        } else if (statusCd == WorkStatus.STATUS_CANCELLED) {
            return false;
        } else return statusCd != WorkStatus.STATUS_HOLD;
    }

    /**
     * Sends a RESUME internal event to resume the activity instance.
     *
     * This may be called in the following cases:
     *   1) received an external event (including the case the message is received before registration)
     *       In this case, the argument message is populated.
     *   2) when register even wait instance, and the even has already arrived. In this case
     *       the argument message null.
     */
    private void resumeActivityInstance(ActivityInstance actInst, String pCompletionCode, Long documentId,
            String message, int delay) throws MdwException, SQLException {
        ProcessInstance pi = edao.getProcessInstance(actInst.getProcessInstanceId());
        if (!this.isProcessInstanceResumable(pi)) {
            logger.info("ProcessInstance in NOT resumable. ProcessInstanceId:" + pi.getId());
        }
        InternalEvent outgoingMsg = InternalEvent.
                createActivityNotifyMessage(actInst, EventType.RESUME, pi.getMasterRequestId(), pCompletionCode);
        if (documentId != null) {        // should be always true
            outgoingMsg.setSecondaryOwnerType(OwnerType.DOCUMENT);
            outgoingMsg.setSecondaryOwnerId(documentId);
        }
        if (message != null && message.length() < 2500) {
            outgoingMsg.addParameter("ExternalEventMessage", message);
        }
        if (this.isProcessInstanceProgressable(pi)) {
            edao.setProcessInstanceStatus(pi.getId(), WorkStatus.STATUS_IN_PROGRESS);
        }
        if (delay > 0) {
            this.sendDelayedInternalEvent(outgoingMsg, delay,
                    ScheduledEvent.INTERNAL_EVENT_PREFIX+actInst.getId(), false);
        } else {
            this.sendInternalEvent(outgoingMsg);
        }
    }

    void sendInternalEvent(InternalEvent event) throws MdwException {
        internalMessenger.sendMessage(event, edao);
    }

    void sendDelayedInternalEvent(InternalEvent event, int delaySeconds, String msgid, boolean isUpdate)
            throws MdwException {
        internalMessenger.sendDelayedMessage(event, delaySeconds, msgid, isUpdate, edao);
    }

    boolean isInService() {
        return inService;
    }

    boolean isInMemory() {
        return null != edao && edao.getPerformanceLevel() >= 9;
    }

    /**
     * Notify registered ProcessMonitors.
     */
    public void notifyMonitors(ProcessInstance processInstance, InternalLogMessage logMessage) {
        // notify registered monitors
        Process process = getMainProcessDefinition(processInstance);
        Package pkg = PackageCache.getPackage(process.getPackageName());
        // runtime context for enablement does not contain hydrated variables map (too expensive)
        List<ProcessMonitor> monitors = MonitorRegistry.getInstance()
                .getProcessMonitors(new ProcessRuntimeContext(null, pkg, process, processInstance,
                        getDataAccess().getPerformanceLevel(), isInService(), new HashMap<>()));
        if (!monitors.isEmpty()) {
            Map<String,Object> vars = new HashMap<>();
            if (processInstance.getVariables() != null) {
                for (VariableInstance var : processInstance.getVariables()) {
                    Object value = var.getData();
                    if (value instanceof DocumentReference) {
                        try {
                            Document docVO = getDocument((DocumentReference) value, false);
                            value = docVO == null ? null : docVO.getObject(var.getType(), pkg);
                        }
                        catch (DataAccessException ex) {
                            logger.severeException(ex.getMessage(), ex);
                        }
                    }
                    vars.put(var.getName(), value);
                }
            }
            ProcessRuntimeContext runtimeContext = new ProcessRuntimeContext(null, pkg, process, processInstance,
                    getDataAccess().getPerformanceLevel(), isInService(), vars);

            for (ProcessMonitor monitor : monitors) {
                try {
                    if (monitor instanceof OfflineMonitor) {
                        @SuppressWarnings("unchecked")
                        OfflineMonitor<ProcessRuntimeContext> processOfflineMonitor = (OfflineMonitor<ProcessRuntimeContext>) monitor;
                        new OfflineMonitorTrigger<>(processOfflineMonitor, runtimeContext).fire(logMessage);
                    }
                    else {
                        if (logMessage == InternalLogMessage.PROCESS_START) {
                            Map<String,Object> updated = monitor.onStart(runtimeContext);
                            if (updated != null) {
                                for (String varName : updated.keySet()) {
                                    if (processInstance.getVariables() == null)
                                        processInstance.setVariables(new ArrayList<>());
                                    Variable varVO = process.getVariable(varName);
                                    if (varVO == null || !varVO.isInput())
                                        throw new ProcessException("Process '" + process.getQualifiedLabel() + "' has no such input variable defined: " + varName);
                                    if (processInstance.getVariable(varName) != null)
                                        throw new ProcessException("Process '" + process.getQualifiedLabel() + "' input variable already populated: " + varName);
                                    if (VariableTranslator.isDocumentReferenceVariable(runtimeContext.getPackage(), varVO.getType())) {
                                        DocumentReference docRef = createDocument(varVO.getType(), OwnerType.VARIABLE_INSTANCE, 0L,
                                                updated.get(varName));
                                        VariableInstance varInst = createVariableInstance(processInstance, varName, docRef);
                                        updateDocumentInfo(docRef, process.getVariable(varInst.getName()).getType(), OwnerType.VARIABLE_INSTANCE,
                                                varInst.getInstanceId(), null, null);
                                        processInstance.getVariables().add(varInst);
                                    }
                                    else {
                                        VariableInstance varInst = createVariableInstance(processInstance, varName, updated.get(varName));
                                        processInstance.getVariables().add(varInst);
                                    }
                                }
                            }
                        }
                        else if (logMessage == InternalLogMessage.PROCESS_ERROR) {
                            monitor.onError(runtimeContext);
                        }
                        else if (logMessage == InternalLogMessage.PROCESS_COMPLETE) {
                            monitor.onFinish(runtimeContext);
                        }
                    }
                }
                catch (Exception ex) {
                    logger.severeException(ex.getMessage(), ex);
                }
            }
        }
    }

    public DocumentReference createActivityExceptionDocument(ProcessInstance processInst,
            ActivityInstance actInstVO, BaseActivity activityImpl, Throwable th) throws DataAccessException {
        ActivityException actEx;
        if (th instanceof ActivityException) {
            actEx = (ActivityException) th;
        }
        else {
            if (th instanceof MdwException)
                actEx = new ActivityException(((MdwException)th).getCode(), th.toString(), th.getCause());
            else
                actEx = new ActivityException(th.toString(), th.getCause());
            actEx.setStackTrace(th.getStackTrace());
        }

        // populate activity context
        if (actInstVO != null) {
            Process process = getProcessDefinition(processInst);
            Package pkg = getPackage(getMainProcessDefinition(processInst));
            if (pkg != null)
                processInst.setPackageName(pkg.getName());
            Activity activity = process.getActivity(actInstVO.getActivityId());

            ActivityImplementor activityImplementor = ImplementorCache.get(activity.getImplementor());
            String category = activityImplementor == null ? GeneralActivity.class.getName() : activityImplementor.getCategory();
            ActivityRuntimeContext runtimeContext = new ActivityRuntimeContext(null, pkg, process, processInst,
                    getDataAccess().getPerformanceLevel(), isInService(), activity, category, actInstVO,
                    activityImpl instanceof SuspendableActivity);
            // TODO option to suppress variables
            if (activityImpl == null) {
                try {
                    processInst.setVariables(getDataAccess().getProcessInstanceVariables(processInst.getId()));
                } catch (SQLException ignored) {}
            }
            for (Variable var : process.getVariables()) {
                try {
                    if (activityImpl != null)
                        runtimeContext.getVariables().put(var.getName(), activityImpl.getVariableValue(var.getName()));
                    else if (processInst.getVariable(var.getName()) != null) {
                        Object value = processInst.getVariable(var.getName()).getData();
                        if (value instanceof DocumentReference) {
                            Document docVO = getDocument((DocumentReference) value, false);
                            value = docVO == null ? null : docVO.getObject(var.getType(), pkg);
                        }
                        runtimeContext.getVariables().put(var.getName(), processInst.getVariable(var.getName()).getData());
                    }
                }
                catch (ActivityException | DataAccessException ex) {
                    engineLogger.error(processInst.getProcessId(), processInst.getId(), actInstVO.getActivityId(), actInstVO.getId(), ex.getMessage(), ex);
                }
            }

            actEx.setRuntimeContext(runtimeContext);
        }

        return createDocument(Exception.class.getName(), OwnerType.ACTIVITY_INSTANCE, actInstVO.getId(), actEx);
    }

    public DocumentReference createProcessExceptionDocument(ProcessInstance processInst, Throwable th) throws DataAccessException {
        ProcessException procEx;
        if (th instanceof ProcessException) {
            procEx = (ProcessException) th;
        }
        else {
            if (th instanceof MdwException)
                procEx = new ProcessException(((MdwException)th).getCode(), th.toString(), th.getCause());
            else
                procEx = new ProcessException(th.toString(), th.getCause());
            procEx.setStackTrace(th.getStackTrace());
        }
        Long procId = 0L;
        if (processInst != null) {
            procId = processInst.getId();
            Process process = getProcessDefinition(processInst);
            Package pkg = getPackage(getMainProcessDefinition(processInst));
            if (pkg != null)
                processInst.setPackageName(pkg.getName());

            ProcessRuntimeContext runtimeContext = new ProcessRuntimeContext(null, pkg, process, processInst,
                    getDataAccess().getPerformanceLevel(), isInService());

            try {
                processInst.setVariables(getDataAccess().getProcessInstanceVariables(processInst.getId()));
            } catch (SQLException ignored) {}

            for (VariableInstance var : processInst.getVariables()) {
                Object value = var.getData();
                if (value instanceof DocumentReference) {
                    try {
                        Document docVO = getDocument((DocumentReference) value, false);
                        value = docVO == null ? null : docVO.getObject(var.getType(), pkg);
                    }
                    catch (DataAccessException ex) {
                        engineLogger.error(processInst.getProcessId(), processInst.getId(), processInst.getMasterRequestId(), ex.getMessage(), ex);
                    }
                }
                runtimeContext.getVariables().put(var.getName(), value);
            }

            procEx.setRuntimeContext(runtimeContext);
        }

        return createDocument(Exception.class.getName(), OwnerType.PROCESS_INSTANCE, procId, procEx);
    }

    private Package getPackage(Process process) {
        if (process.getPackageName() == null)
            return null;
        else
            return PackageCache.getPackage(process.getPackageName());
    }

    private GeneralActivity getActivityInstance(Package pkg, String implClass) throws Exception {
        ActivityImplementor activityImplementor = ImplementorCache.get(implClass);
        if (activityImplementor != null && activityImplementor.getSupplier() != null) {
            return activityImplementor.getSupplier().get();
        }
        return pkg.getActivityImplementor(implClass);
    }
}
