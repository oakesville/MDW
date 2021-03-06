package com.centurylink.mdw.model.user;

import com.centurylink.mdw.model.InstanceList;
import com.centurylink.mdw.model.JsonObject;
import com.centurylink.mdw.model.Jsonable;
import com.centurylink.mdw.util.DateHelper;
import io.swagger.annotations.ApiModel;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@ApiModel(value="WorkgroupList", description="List of MDW workgroups")
public class WorkgroupList implements Jsonable, InstanceList<Workgroup> {

    public WorkgroupList(List<Workgroup> groups) {
        this.groups = groups;
        this.count = groups.size();
    }

    public WorkgroupList(String json) throws JSONException {
        JSONObject jsonObj = new JsonObject(json);
        if (jsonObj.has("retrieveDate"))
            retrieveDate = DateHelper.serviceStringToDate(jsonObj.getString("retrieveDate"));
        if (jsonObj.has("count"))
            count = jsonObj.getInt("count");
        if (jsonObj.has("workgroups")) {
            JSONArray groupList = jsonObj.getJSONArray("workgroups");
            for (int i = 0; i < groupList.length(); i++)
                groups.add(new Workgroup((JSONObject)groupList.get(i)));
        }
    }

    private Date retrieveDate;
    public Date getRetrieveDate() { return retrieveDate; }
    public void setRetrieveDate(Date d) { this.retrieveDate = d; }

    private int count;
    public int getCount() { return count; }
    public void setCount(int ct) { this.count = ct; }

    public long getTotal() { return count; }  // no pagination

    private List<Workgroup> groups = new ArrayList<>();
    public List<Workgroup> getGroups() { return groups; }
    public void setGroups(List<Workgroup> groups) { this.groups = groups; }

    public List<Workgroup> getItems() {
        return groups;
    }

    public int getIndex(String id) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).getId().toString().equals(id))
                return i;
        }
        return -1;
    }

    public Workgroup get(String name) {
        for (Workgroup group : groups) {
            if (group.getName().equals(name))
                return group;
        }
        return null;
    }

    public JSONObject getJson() throws JSONException {
        JSONObject json = create();
        json.put("retrieveDate", DateHelper.serviceDateToString(getRetrieveDate()));
        json.put("count", count);
        JSONArray array = new JSONArray();
        if (groups != null) {
            for (Workgroup group : groups)
                array.put(group.getJson());
        }
        json.put("workgroups", array);
        return json;
    }

    public String getJsonName() {
        return "Workgroups";
    }
}
