package com.centurylink.mdw.model.request;

import com.centurylink.mdw.model.Jsonable;
import org.json.JSONException;
import org.json.JSONObject;

public class Response implements Jsonable {

    private String content;
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    private Object object;
    public Object getObject() { return object; }
    public void setObject(Object object) { this.object = object; }

    private Integer statusCode;
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer code) { this.statusCode = code; }

    private String statusMessage;
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String message) { this.statusMessage = message; }

    private String path;
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    private JSONObject meta;
    public JSONObject getMeta() { return meta; }
    public void setMeta(JSONObject info) { meta = info; }

    public Response() {
    }

    public Response(String content) {
        this.content = content;
    }

    public Response(JSONObject json) throws JSONException {
        if (json.has("content"))
            this.content = json.getString("content");
        if (json.has("statusCode"))
            this.statusCode = json.getInt("statusCode");
        if (json.has("statusMessage"))
            this.statusMessage = json.getString("statusMessage");
    }

    public boolean isEmpty() {
        return content == null || content.isEmpty();
    }

    public JSONObject getJson() throws JSONException {
        JSONObject json = create();
        if (content != null)
            json.put("content", content);
        if (statusCode != null)
            json.put("statusCode", statusCode);
        if (statusMessage != null)
            json.put("statusMessage", statusMessage);
        return json;
    }

    public String getJsonName() {
        return "response";
    }
}
