package com.centurylink.mdw.model.workflow;

import com.centurylink.mdw.model.report.Aggregate;
import com.centurylink.mdw.model.Jsonable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Aggregated process instances for a particular definition or status.
 */
public class ProcessAggregate implements Aggregate, Jsonable {

    private long id;
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    private String name;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    private String version = "0";
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    private String packageName;
    public String getPackageName() { return packageName; }
    public void setPackageName(String pkg) { this.packageName = pkg; }

    private long value;
    public long getValue() { return value; }
    public void setValue(long value) { this.value = value; }

    private long count = -1;
    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public ProcessAggregate(long value) {
        this.value = value;
    }

    @SuppressWarnings("unused")
    public ProcessAggregate(JSONObject json) throws JSONException {
        value = json.getLong("value");
        if (json.has("count"))
            count = json.getLong("count");
        if (json.has("id"))
            id = json.getLong("id");
        if (json.has("name"))
            name = json.getString("name");
        if (json.has("version"))
            version = json.getString("version");
        if (json.has("packageName"))
            packageName = json.getString("packageName");
    }

    public String getJsonName() {
        return "processCount";
    }

    public JSONObject getJson() throws JSONException {
        JSONObject json = create();
        json.put("value", value);
        if (count > -1)
            json.put("count", count);
        if (id >= 0)
            json.put("id", id);
        if (name != null)
            json.put("name", name);
        if (version != null)
            json.put("version", version);
        if (packageName != null)
            json.put("packageName", packageName);
        return json;
    }
}
