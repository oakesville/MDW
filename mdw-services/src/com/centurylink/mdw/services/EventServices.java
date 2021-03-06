package com.centurylink.mdw.services;

import com.centurylink.mdw.common.service.ServiceException;
import com.centurylink.mdw.dataaccess.DataAccessException;
import com.centurylink.mdw.model.event.EventInstance;
import com.centurylink.mdw.model.event.EventLog;
import com.centurylink.mdw.model.user.UserAction;
import com.centurylink.mdw.model.variable.VariableInstance;
import com.centurylink.mdw.model.workflow.Package;
import com.centurylink.mdw.model.workflow.Process;
import com.centurylink.mdw.model.workflow.*;
import com.centurylink.mdw.services.event.ServiceHandler;
import com.centurylink.mdw.services.event.WorkflowHandler;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

public interface EventServices {

    void createAuditLog(UserAction userAction)
            throws DataAccessException, EventException;

    Long createEventLog(String eventName, String category, String subcategory, String source,
            String ownerType, Long ownerId, String user, String modUser, String comments)
            throws DataAccessException, EventException;

    List<EventLog> getEventLogs(String eventName, String source,
            String ownerType, Long ownerId) throws ServiceException;

    Integer notifyProcess(String eventName, Long eventInstanceId, String message, int delay)
            throws DataAccessException, EventException;

    /**
     * get variable instance by its ID
     */
    VariableInstance getVariableInstance(Long varInstId)
            throws DataAccessException;

    /**
     * Get variable instance for a process instance.
     * The method does not take care of embedded process, so the caller needs to pass
     * the parent process instance id when looking for variables for embedded process
     */
    VariableInstance getVariableInstance(Long procInstId, String name)
            throws DataAccessException;

    /**
     * Set the variable instance value.
     * The method does not take care of document variables, for which you must
     * pass in DocumentReferenceObject.
     * The method does not take care of embedded process, so the caller needs to pass
     * the parent process instance id when looking for variables for embedded process
     *
     */
    VariableInstance setVariableInstance(Long procInstId, String name, Object value, Package pkg)
            throws DataAccessException;

    void updateDocumentContent(Long docid, Object docObj, Package pkg)
            throws DataAccessException;

    void updateDocumentInfo(Long docid, String documentType, String ownerType, Long ownerId)
            throws DataAccessException;

    Long createDocument(String variableType, String ownerType, Long ownerId, Object docObj, Package pkg)
            throws DataAccessException;

    Long createDocument(String variableType, String ownerType, Long ownerId, Object docObj, Package pkg, String path)
            throws DataAccessException;

    void sendDelayEventsToWaitActivities(String masterRequestId)
            throws DataAccessException, ProcessException;

    void cancelProcess(Long processInstanceId) throws DataAccessException, ServiceException;

    void retryActivity(Long activityId, Long activityInstId)
            throws DataAccessException, ProcessException;

    /**
     * Skip the activity by sending an activity finish event, with the given completion code.
     * The status of the activity instance is ???
     */
    void skipActivity(Long activityId, Long activityInstId, String completionCode)
            throws DataAccessException, ProcessException;

    /**
     * Returns the transition instance object by ID
     *
     * @return transition instance object
     */
    TransitionInstance getWorkTransitionInstance(Long transInstId)
            throws DataAccessException, ProcessException;

    /**
     * Returns the ActivityInstance identified by the passed in Id
     */
    ActivityInstance getActivityInstance(Long pActivityInstId)
            throws ProcessException, DataAccessException;

    /**
     * Returns the activity instances by process name, activity logical ID, and  master request ID.
     *
     * @return the list of activity instances. If the process definition or the activity
     * with the given logical ID is not found, null
     * or no such activity instances are found, an empty list is returned.
     */
    List<ActivityInstance> getActivityInstances(String masterRequestId,
            String processName, String activityLogicalId)
            throws ProcessException, DataAccessException;

    /**
     * Returns the Process instance object identified by the passed in Id
     */
    ProcessInstance getProcessInstance(Long procInstId)
            throws ProcessException, DataAccessException;

    /**
     * Returns the process instances by process name and master request ID.
     *
     * @return the list of process instances. If the process definition is not found, null
     * is returned; if process definition is found but no process instances are found,
     * an empty list is returned.
     */
    List<ProcessInstance> getProcessInstances(String masterRequestId, String processName)
            throws ProcessException, DataAccessException;

    void updateEventInstance(String eventName,
            Long documentId, Integer status, Date consumeDate, String auxdata, String reference, int preserveSeconds)
            throws DataAccessException;

    void updateEventInstance(String eventName,
            Long documentId, Integer status, Date consumeDate, String auxdata, String reference, int preserveSeconds, String comments)
            throws DataAccessException;

    void deleteEventInstance(String eventName) throws DataAccessException;
    void deleteEventWaitInstance(String eventName) throws DataAccessException;

    EventInstance getEventInstance(String eventName) throws DataAccessException;

    /**
     * Register a service handler to respond to MDW listener requests.
     * The handlers's getProtocol() and getPath() methods are used to uniquely
     * identify the types of requests that it responds to.
     */
    void registerServiceHandler(ServiceHandler handler)
            throws EventException;

    void unregisterServiceHandler(ServiceHandler handler)
            throws EventException;

    ServiceHandler getServiceHandler(String protocol, String path)
            throws EventException;

    /**
     * Register a workflow handler to respond to activity triggers.
     * The handlers's getAsset() and getParameters() methods are used to uniquely identify
     * the types of flows the handler responds to.
     * Note: asset should include the workflow package (eg: MyPackage/MyCamelRoute.xml).
     */
    void registerWorkflowHandler(WorkflowHandler handler)
            throws EventException;

    void unregisterWorkflowHandler(WorkflowHandler handler)
            throws EventException;

    WorkflowHandler getWorkflowHandler(String asset, Map<String, String> parameters)
            throws EventException;

    Process findProcessByProcessInstanceId(Long processInstanceId)
            throws DataAccessException, ProcessException, IOException;

}