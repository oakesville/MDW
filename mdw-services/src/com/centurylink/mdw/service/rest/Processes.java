package com.centurylink.mdw.service.rest;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.common.service.Query;
import com.centurylink.mdw.common.service.ServiceException;
import com.centurylink.mdw.common.service.types.StatusMessage;
import com.centurylink.mdw.constant.OwnerType;
import com.centurylink.mdw.dataaccess.DataAccessException;
import com.centurylink.mdw.dataaccess.DatabaseAccess;
import com.centurylink.mdw.model.*;
import com.centurylink.mdw.model.Value.Display;
import com.centurylink.mdw.model.listener.Listener;
import com.centurylink.mdw.model.report.Hotspot;
import com.centurylink.mdw.model.report.Insight;
import com.centurylink.mdw.model.report.Timepoint;
import com.centurylink.mdw.model.user.Role;
import com.centurylink.mdw.model.user.UserAction.Entity;
import com.centurylink.mdw.model.variable.Variable;
import com.centurylink.mdw.model.workflow.Process;
import com.centurylink.mdw.model.workflow.*;
import com.centurylink.mdw.service.data.process.HierarchyCache;
import com.centurylink.mdw.service.data.process.ProcessCache;
import com.centurylink.mdw.services.DesignServices;
import com.centurylink.mdw.services.ServiceLocator;
import com.centurylink.mdw.services.WorkflowServices;
import com.centurylink.mdw.services.rest.JsonRestService;
import com.centurylink.mdw.util.DateHelper;
import com.centurylink.mdw.util.JsonUtil;
import com.centurylink.mdw.util.log.ActivityLog;
import com.centurylink.mdw.util.log.ActivityLogLine;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.ws.rs.Path;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;

@Path("/Processes")
@Api("Workflow process instances and values")
public class Processes extends JsonRestService implements JsonExportable {

    @Override
    protected Entity getEntity(String path, Object content, Map<String,String> headers) {
        return Entity.ProcessInstance;
    }

    @Override
    public List<String> getRoles(String path) {
        List<String> roles = super.getRoles(path);
        roles.add(Role.PROCESS_EXECUTION);
        return roles;
    }

    private WorkflowServices getWorkflowServices() {
        return ServiceLocator.getWorkflowServices();
    }
    private DesignServices getDesignServices() {
        return ServiceLocator.getDesignServices();
    }

    /**
     * Retrieve process instance(s).
     */
    @Override
    @Path("/{instanceId}/{subData}/{subId}")
    @ApiOperation(value="Retrieve a process or process values, query many processes, or perform throughput queries",
        notes="If instanceId and special are not present, returns a page of processes that meet query criteria. "
          + "If {special} is 'run', then {subData} must be procDefId and an empty ProcessRun is returned. "
          + "If {subData} is 'values', and then {subId} can be varName or expression (otherwise all populated values are returned). "
          + "If {subData} is 'summary' then a only summary-level process info is returned.",
        response=ProcessInstance.class, responseContainer="List")
    public JSONObject get(String path, Map<String,String> headers)
    throws ServiceException, JSONException {
        WorkflowServices workflowServices = ServiceLocator.getWorkflowServices();
        Query query = getQuery(path, headers);
        try {
            String segOne = getSegment(path, 1);
            if (segOne != null) {
                try {
                    long id = Long.parseLong(segOne);
                    String segTwo = getSegment(path, 2);
                    if ("values".equalsIgnoreCase(segTwo)) {
                        String varName = getSegment(path, 3);
                        if (varName != null) {
                            // individual value
                            Value value = workflowServices.getProcessValue(id, varName);
                            return value.getJson();
                        }
                        else {
                            // all values
                            Map<String,Value> values = workflowServices.getProcessValues(id, query.getBooleanFilter("includeEmpty"));
                            JSONObject valuesJson = new JsonObject();
                            for (String name : values.keySet()) {
                                valuesJson.put(name, values.get(name).getJson());
                            }
                            return valuesJson;
                        }
                    }
                    else if ("summary".equals(segTwo)) {
                        return getSummary(id);
                    }
                    else if ("log".equals(segTwo)) {
                        ActivityLog log = null;
                        Long[] activityInstanceIds = query.getLongArrayFilter("activityInstanceIds");
                        if (activityInstanceIds != null) {
                            log =  getWorkflowServices().getProcessLog(id, activityInstanceIds);
                        }
                        else {
                            boolean withActivities = query.getBooleanFilter("withActivities");
                            log =  getWorkflowServices().getProcessLog(id, withActivities);
                        }
                        if (log == null)
                            throw new ServiceException(ServiceException.NOT_FOUND, "Log not found for process: " + id);
                        return log.getJson();
                    }
                    else {
                        JSONObject json = getProcess(id).getJson();
                        json.put("retrieveDate", DateHelper.serviceDateToString(DatabaseAccess.getDbDate()));
                        return json;
                    }
                }
                catch (NumberFormatException ex) {
                    // path must be special
                    if (segOne.equals("definitions")) {
                        return getDefinitions(query).getJson();
                    }
                    else if (segOne.equals("run")) {
                        String[] segments = getSegments(path);
                        if (segments.length != 3 && segments.length != 4)
                            throw new ServiceException(ServiceException.BAD_REQUEST, "Missing path segment {subData} (procDefId|assetPath)");
                        if (segments.length == 3) {
                            try {
                                return getProcessRun(Long.parseLong(segments[2]), getAuthUser(headers)).getJson();
                            }
                            catch (NumberFormatException nfe) {
                                throw new ServiceException(ServiceException.BAD_REQUEST, "Bad definitionId: " + segments[2]);
                            }
                        }
                        else {
                            return getProcessRun(segments[2] + '/' + segments[3], getAuthUser(headers)).getJson();
                        }
                    }
                    else if (segOne.equals("tops")) {
                        return getTops(query).getJson();
                    }
                    else if (segOne.equals("breakdown")) {
                        return getBreakdown(query).getJson();
                    }
                    else if (segOne.equals("insights")) {
                        JsonList<Insight> jsonList = getInsights(query);
                        JSONObject json = jsonList.getJson();
                        String trend = query.getFilter("trend");
                        if ("completionTime".equals(trend)) {
                            List<Timepoint> timepoints = workflowServices.getProcessTrend(query);
                            json.put("trend", new JsonList<>(timepoints, "trend").getJson().getJSONArray("trend"));
                        }
                        return json;
                    }
                    else if (segOne.equals("hotspots")) {
                        return getHotspots(query).getJson();
                    }
                    else {
                        throw new ServiceException(ServiceException.BAD_REQUEST, "Unsupported path segment: " + segOne);
                    }
                }
            }
            else {
                long triggerId = query.getLongFilter("triggerId");
                if (triggerId > 0) {
                    // retrieve instance by trigger -- just send summary
                    return getSummaryJson(workflowServices.getProcessForTrigger(triggerId));
                }
                else {
                    long callHierarchyFor = query.getLongFilter("callHierarchyFor");
                    if (callHierarchyFor != -1) {
                        Linked<ProcessInstance> linkedInstance = ServiceLocator.getWorkflowServices().getCallHierearchy(callHierarchyFor);
                        return linkedInstance.getJson(1);
                    }
                    else {
                        // general process instance list query
                        ProcessList processList = workflowServices.getProcesses(query);
                        if (query.getLongFilter("activityInstanceId") > 0) {
                            // retrieving summary for activity instance
                            if (processList.getCount() == 0)
                                throw new ServiceException(ServiceException.NOT_FOUND, "Process instance not found: " + query);
                            else if (OwnerType.MAIN_PROCESS_INSTANCE.equals(processList.getProcesses().get(0).getOwner())) {
                                return getSummaryJson(workflowServices.getProcess(processList.getProcesses().get(0).getOwnerId(), true));
                            }
                            return getSummaryJson(processList.getProcesses().get(0));
                        }
                        else {
                            return processList.getJson();
                        }
                    }
                }
            }
        }
        catch (ServiceException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new ServiceException(ServiceException.INTERNAL_ERROR, ex.getMessage(), ex);
        }
    }

    /**
     * Returns main process instance if embedded instance.
     */
    @Path("/{instanceId}/summary")
    public JSONObject getSummary(Long instanceId) throws ServiceException {
        ProcessInstance process = getWorkflowServices().getProcess(instanceId);
        if (process.isEmbedded())
            process = getWorkflowServices().getProcess(process.getOwnerId());
        return getSummaryJson(process);
    }

    protected JSONObject getSummaryJson(ProcessInstance process) {
        JSONObject summary = new JsonObject();
        summary.put("id", process.getId());
        summary.put("name", process.getProcessName());
        summary.put("packageName", process.getPackageName());
        summary.put("version", process.getProcessVersion());
        summary.put("masterRequestId", process.getMasterRequestId());
        summary.put("definitionId", process.getProcessId());
        summary.put("status", process.getStatus());
        summary.put("template", process.getTemplate());
        if (process.getTemplate() != null) {
            summary.put("templatePackage", process.getTemplatePackage());
            summary.put("templateVersion", process.getTemplateVersion());
            Process latestTemplate = ProcessCache.getProcess(process.getTemplatePackage() + "/" + process.getTemplate());
            // If null it means it is archived but was renamed or removed from current assets
            if (latestTemplate == null || !latestTemplate.getId().equals(process.getProcessId()))
                summary.put("archived", true);
            if (HierarchyCache.hasMilestones(latestTemplate.getId()))
                summary.put("hasMilestones", true);
        }
        else {
            Process latest = ProcessCache.getProcess(process.getPackageName() + "/" + process.getProcessName());
            // If null it means it is archived but was renamed or removed from current assets
            if (latest == null || !latest.getId().equals(process.getProcessId()))
                summary.put("archived", true);
            if (latest != null && HierarchyCache.hasMilestones(latest.getId()))
                summary.put("hasMilestones", true);
        }
        return summary;
    }

    @Override
    @Path("/{instanceId}/values")
    @ApiOperation(value="Update value(s) for a process instance",
        notes="Values are created or updated based on the passed JSON object.",
        response=StatusMessage.class)
    @ApiImplicitParams({
        @ApiImplicitParam(name="Values", paramType="body", dataType="java.lang.Object")})
    public JSONObject put(String path, JSONObject content, Map<String,String> headers)
            throws ServiceException, JSONException {
        String id = getSegment(path, 1);
        if (id == null)
            throw new ServiceException(HTTP_400_BAD_REQUEST, "Missing path segment: {instanceId}");
        try {
            Long instanceId = Long.parseLong(id);
            if ("values".equals(getSegment(path, 2))) {
                Map<String,String> values = JsonUtil.getMap(content);
                WorkflowServices workflowServices = ServiceLocator.getWorkflowServices();
                for (String varName : values.keySet()) {
                    workflowServices.setVariable(instanceId, varName, values.get(varName));
                }
                return null;
            }
            else {
                throw new ServiceException(HTTP_400_BAD_REQUEST, "Missing path segment: values");
            }
        }
        catch (NumberFormatException ex) {
            throw new ServiceException(HTTP_400_BAD_REQUEST, "Invalid instance id: " + id);
        }
        catch (ServiceException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new ServiceException(ex.getMessage(), ex);
        }
    }

    @Override
    @Path("/run/{definitionId|processPath}")
    @ApiOperation(value="Run a process", response=ProcessRun.class)
    @ApiImplicitParams({
        @ApiImplicitParam(name="Values", paramType="body", dataType="com.centurylink.mdw.model.workflow.ProcessRun")})
    public JSONObject post(String path, JSONObject content, Map<String,String> headers)
            throws ServiceException, JSONException {
        try {
            String[] segments = getSegments(path);
            if (segments.length > 1 && segments[1].equals("run")) {
                WorkflowServices workflowServices = ServiceLocator.getWorkflowServices();
                ProcessRun run = new ProcessRun(content);
                if (headers.get("genmasterrequestid") != null) {
                    run.setMasterRequestId(getAuthUser(headers) + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
                }
                if (run.getMasterRequestId() == null || run.getMasterRequestId().isEmpty())
                    throw new ServiceException(ServiceException.BAD_REQUEST, "Missing master request id");
                if (ServiceLocator.getRequestServices().getMasterRequest(run.getMasterRequestId()) != null)
                    throw new ServiceException(ServiceException.BAD_REQUEST, "Master request ID: " + run.getMasterRequestId() + " already exists");

                if (segments.length !=3 && segments.length != 4)
                    throw new ServiceException(ServiceException.BAD_REQUEST, "Bad path: " + path);

                Process proc;
                if (segments.length == 3) {
                    String defId = segments[2];
                    try {
                        Long definitionId = Long.parseLong(defId);
                        if (!definitionId.equals(run.getDefinitionId()))
                            throw new ServiceException(ServiceException.BAD_REQUEST, "Path/body mismatch for definitionId: " + definitionId + "/" + run.getDefinitionId());
                        proc = ProcessCache.getProcess(definitionId);
                        if (proc == null)
                            throw new ServiceException(ServiceException.NOT_FOUND, "Process not found: " + definitionId);
                    }
                    catch (NumberFormatException ex) {
                        throw new ServiceException(ServiceException.BAD_REQUEST, "Bad definitionId: " + defId);
                    }
                }
                else {
                    String procPath = segments[2] + "/" + segments[3];
                    proc = ProcessCache.getProcess(procPath);
                    if (proc == null)
                        throw new ServiceException(ServiceException.NOT_FOUND, "Process not found: " + procPath);
                }
                run.setDefinitionId(proc.getId());
                String validationError = "";
                Map<String,Value> inputValues = getInputValues(proc);
                for (String inputVarName : inputValues.keySet()) {
                    Value inputVar = inputValues.get(inputVarName);
                    Display display = inputVar.getDisplay();
                    boolean populated = run.getValueNames().contains(inputVarName);
                    if (display == Display.ReadOnly && populated) {
                        validationError += (validationError.isEmpty() ? "" : ", ") + "ReadOnly: "
                                + (inputVar.getLabel() == null ? inputVar.getName() : inputVar.getLabel());
                    }
                    else if (display == Display.Required && !populated) {
                        validationError += (validationError.isEmpty() ? "" : ", ") + "Required: "
                                + (inputVar.getLabel() == null ? inputVar.getName() : inputVar.getLabel());
                    }
                }
                if (!validationError.isEmpty())
                    throw new ServiceException(ServiceException.BAD_REQUEST, validationError);
                run = workflowServices.runProcess(run);
                headers.put("Location", ApplicationContext.getServicesUrl() + "/services/Processes/" + run.getInstanceId());
                headers.put(Listener.METAINFO_HTTP_STATUS_CODE, String.valueOf(Status.CREATED.getCode()));
                return run.getJson();
            }
            else {
                throw new ServiceException(ServiceException.BAD_REQUEST, "Missing path segment: run");
            }
        }
        catch (ServiceException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new ServiceException(ex.getMessage(), ex);
        }
    }

    @Override
    @Path("/{instanceId}")
    @ApiOperation(value="Cancel a process instance")
    public JSONObject delete(String path, JSONObject content, Map<String, String> headers) throws ServiceException {
        String processInstanceSegment = getSegment(path, 1);
        if (processInstanceSegment == null)
            throw new ServiceException(ServiceException.BAD_REQUEST, "Missing path segment: instanceId");
        try {
            Long processInstanceId = Long.parseLong(processInstanceSegment);
            ServiceLocator.getEventServices().cancelProcess(processInstanceId);
            headers.put(Listener.METAINFO_HTTP_STATUS_CODE, String.valueOf(Status.ACCEPTED.getCode()));
            return null;
        } catch (NumberFormatException ex) {
            throw new ServiceException(ServiceException.BAD_REQUEST, "Invalid instanceId: " + processInstanceSegment);
        } catch (DataAccessException ex) {
            throw new ServiceException(ServiceException.INTERNAL_ERROR, ex);
        }
    }

    @Override
    public Jsonable toJsonable(Query query, JSONObject json) throws JSONException {
        String path = query.getPath();
        try {
            if (json.has(ProcessList.PROCESS_INSTANCES)) {
                return new ProcessList(ProcessList.PROCESS_INSTANCES, json);
            }
            else if ("Processes/breakdown".equals(path)) {
                return new JsonListMap<>(json, ProcessAggregate.class);
            }
            else if (path != null && path.startsWith("Processes/") && path.endsWith("/log")) {
                JSONObject listObj = new JSONObject();
                listObj.put("Process Log", json.getJSONArray("logLines"));
                return new JsonList<>(listObj, ActivityLogLine.class);
            }
            else {
                throw new JSONException("Unsupported export type for query: " + query);
            }
        }
        catch (ParseException ex) {
            throw new JSONException(ex);
        }
    }

    @Override
    public String getExportName() { return "Processes"; }

    private Map<String,String> getVariables(Map<String,String> params) {
        Map<String,String> variables = new HashMap<>();
        for (String key : params.keySet()) {
            if (!processParams.contains(key) && !standardParams.contains(key)) {
                variables.put(key, params.get(key));
            }
        }
        return variables.isEmpty() ? null : variables;
    }

    private Map<String,String> getCriteria(Map<String,String> params) {
        Map<String,String> criteria = new HashMap<>();
        for (String key : params.keySet()) {
            if (processParams.contains(key))
                criteria.put(key, params.get(key));
        }
        return criteria.isEmpty() ? null : criteria;
    }

    private static final List<String> processParams = Arrays.asList(
            "processId",
            "processIdList",
            "processName",
            "id",
            "ownerId",
            "owner",
            "masterRequestId",
            "masterRequestIdIgnoreCase",
            "statusCode",
            "startDateFrom",
            "startDatefrom",
            "startDateTo",
            "startDateto",
            "endDateFrom",
            "endDatefrom",
            "endDateTo",
            "endDateto"
    );

    private static final List<String> standardParams = Arrays.asList(
            "pageIndex",
            "pageSize",
            "orderBy",
            "mdw-app",
            "format"
    );

    private Map<String,Value> getInputValues(Process process) {
        Map<String,Value> inputVals = new HashMap<>();
        for (Variable var : process.getVariables()) {
            if (var.isInput()) {
                inputVals.put(var.getName(), var.toValue());
            }
        }
        return inputVals;
    }

    @Path("/definitions")
    public JsonArray getDefinitions(Query query) throws ServiceException {
        List<Process> processVOs = getDesignServices().getProcessDefinitions(query);
        JSONArray jsonProcesses = new JSONArray();
        for (Process processVO : processVOs) {
            JSONObject jsonProcess = new JsonObject();
            jsonProcess.put("packageName", processVO.getPackageName());
            jsonProcess.put("processId", processVO.getId());
            jsonProcess.put("name", processVO.getName());
            jsonProcess.put("version", processVO.getVersionString());
            jsonProcesses.put(jsonProcess);
        }
        return new JsonArray(jsonProcesses);
    }

    @Path("/run/{definitionId}")
    public ProcessRun getProcessRun(Long definitionId, String authUser) throws ServiceException {
        try {
            Process definition = getDesignServices().getProcessDefinition(definitionId);
            if (definition == null)
                throw new ServiceException(ServiceException.NOT_FOUND, "Process definition not found: " + definitionId);
            return getProcessRun(definition, authUser);
        } catch (IOException ex) {
            throw new ServiceException(ServiceException.INTERNAL_ERROR, "Error loading process " + definitionId, ex);
        }
    }

    @Path("/run/{package}/{process}")
    public ProcessRun getProcessRun(String assetPath, String authUser) throws ServiceException {
        Process definition = getDesignServices().getProcessDefinition(assetPath, null);
        if (definition == null)
            throw new ServiceException(ServiceException.NOT_FOUND, "Process definition not found: " + assetPath);
        return getProcessRun(definition, authUser);
    }

    private ProcessRun getProcessRun(Process processDefinition, String user) {
        ProcessRun processRun = new ProcessRun();
        processRun.setDefinitionId(processDefinition.getId());
        processRun.setMasterRequestId(user + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        processRun.setValues(getInputValues(processDefinition));
        return processRun;
    }

    @Path("/{instanceId}")
    public ProcessInstance getProcess(Long instanceId) throws ServiceException {
        return getWorkflowServices().getProcess(instanceId, true);
    }

    @Path("/tops")
    public JsonArray getTops(Query query) throws ServiceException {
        List<ProcessAggregate> list = getWorkflowServices().getTopProcesses(query);
        JSONArray processArray = new JSONArray();
        for (ProcessAggregate processAggregate : list) {
            processArray.put(processAggregate.getJson());
        }
        return new JsonArray(processArray);
    }

    @Path("/breakdown")
    public JsonListMap<ProcessAggregate> getBreakdown(Query query) throws ServiceException {
        TreeMap<Instant,List<ProcessAggregate>> instMap = getWorkflowServices().getProcessBreakdown(query);
        LinkedHashMap<String,List<ProcessAggregate>> listMap = new LinkedHashMap<>();
        for (Instant instant : instMap.keySet()) {
            List<ProcessAggregate> processAggregates = instMap.get(instant);
            listMap.put(Query.getString(instant), processAggregates);
        }

        return new JsonListMap<>(listMap);
    }

    @Path("/insights")
    public JsonList<Insight> getInsights(Query query) throws ServiceException {
        List<Insight> processInsights = getWorkflowServices().getProcessInsights(query);
        JsonList<Insight> jsonList = new JsonList<>(processInsights, "insights");
        jsonList.setTotal(processInsights.size());
        return jsonList;
    }

    @Path("/hotspots")
    public JsonList<Hotspot> getHotspots(Query query) throws ServiceException {
        List<Hotspot> processHotspots = getWorkflowServices().getProcessHotspots(query);
        JsonList<Hotspot> jsonList = new JsonList<>(processHotspots, "hotspots");
        jsonList.setTotal(processHotspots.size());
        return jsonList;
    }
}
