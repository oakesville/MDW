package com.centurylink.mdw.model.workflow;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Aggregated activity instance count for a particular definition or status.
 */
public class ActivityAggregate extends ProcessAggregate {

    private long processId;
    public long getProcessId() { return processId; }
    public void setProcessId(long processId) { this.processId = processId; }

    private String activityId;
    public String getActivityId() { return activityId; }
    public void setActivityId(String id) { this.activityId = id; }

    private String definitionId;
    public String getDefinitionId() { return definitionId;}
    public void setDefinitionId(String definitionId) { this.definitionId = definitionId; }

    // separate from name, which includes process qualifier
    private String activityName;
    public String getActivityName() { return activityName; }
    public void setActivityName(String name) { this.activityName = name; }

    private String processName;
    public String getProcessName() { return processName; }
    public void setProcessName(String name) { this.processName = name; }

    public ActivityAggregate(long count) {
        super(count);
    }

    @SuppressWarnings("unused")
    public ActivityAggregate(JSONObject json) throws JSONException {
        super(json.getLong("value"));
        if (json.has("id"))
            activityId = json.getString("id");
        if (json.has("name"))
            setName(json.getString("name"));
        if (json.has("processId"))
            processId = json.getLong("processId");
        if (json.has("definitionId"))
            definitionId = json.getString("definitionId");
        if (json.has("activityName"))
            activityName = json.getString("activityName");
        if (json.has("processName"))
            processName = json.getString("processName");
        if (json.has("version"))
            setVersion(json.getString("version"));
        if (json.has("packageName"))
            setPackageName(json.getString("packageName"));
        if (json.has("count"))
           setCount(json.getLong("count"));
    }

    public String getJsonName() {
        return "activityCount";
    }

    public JSONObject getJson() throws JSONException {
        JSONObject json = create();
        json.put("value", getValue());
        if (activityId != null)
            json.put("id", activityId);
        else if (getId() > 0)
            json.put("id", getId());
        if (getName() != null)
            json.put("name", getName());
        if (processId > 0)
            json.put("processId", processId);
        if (definitionId != null)
            json.put("definitionId", definitionId);
        if (activityName != null)
            json.put("activityName", activityName);
        if (processName != null)
            json.put("processName", processName);
        if (getVersion() != null)
            json.put("version", getVersion());
        if (getPackageName() != null)
            json.put("packageName", getPackageName());
        if (getCount() > -1)
            json.put("count", getCount());
        return json;
    }
}
