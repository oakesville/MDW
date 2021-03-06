package com.centurylink.mdw.model.task;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import com.centurylink.mdw.model.Jsonable;

public class TaskIndexes implements Jsonable {

    private long taskInstanceId;
    public long getTaskInstanceId() { return taskInstanceId; }

    private Map<String,String> indexes;
    public Map<String,String> getIndexes() { return indexes; }

    public String getIndexValue(String key) {
        return indexes.get(key);
    }

    public void setIndexValue(String key, String value) {
        indexes.put(key, value);
    }

    public TaskIndexes(long taskInstanceId) {
        this.taskInstanceId = taskInstanceId;
        indexes = new HashMap<String,String>();
    }

    public TaskIndexes(long taskInstanceId, Map<String,String> indexes) {
        this.taskInstanceId = taskInstanceId;
        this.indexes = indexes;
    }

    public TaskIndexes(JSONObject json) throws JSONException {
        taskInstanceId = json.getLong("taskInstanceId");
        indexes = new HashMap<String,String>();
        if (json.has("indexes")) {
            JSONObject indexJson = json.getJSONObject("indexes");
            String[] names = JSONObject.getNames(indexJson);
            if (names != null) {
                for (String name : names) {
                    indexes.put(name, indexJson.getString(name));
                }
            }
        }
    }

    public JSONObject getJson() throws JSONException {

        JSONObject taskInstance = create();
        taskInstance.put("taskInstanceId", taskInstanceId);

        if (!indexes.isEmpty()) {
            JSONObject indices = create();
            for (String key : indexes.keySet()) {
                indices.put(key, indexes.get(key));
            }
            taskInstance.put("indexes", indices);
        }

        return taskInstance;
    }

    public String getJsonName() {
        return "TaskInstanceIndexes";
    }

}
