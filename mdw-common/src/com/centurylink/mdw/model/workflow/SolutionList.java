package com.centurylink.mdw.model.workflow;

import com.centurylink.mdw.model.InstanceList;
import com.centurylink.mdw.model.Jsonable;
import com.centurylink.mdw.util.DateHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.List;

public class SolutionList implements Jsonable, InstanceList<Solution> {

    public SolutionList(List<Solution> solutions) {
        this.solutions = solutions;
        this.count = solutions.size();
    }

    private List<Solution> solutions;
    public List<Solution> getSolutions() { return solutions; }
    public void setSolutions(List<Solution> solutions) { this.solutions = solutions; }

    private Date retrieveDate;
    public Date getRetrieveDate() { return retrieveDate; }
    public void setRetrieveDate(Date d) { this.retrieveDate = d; }

    private int count;
    public int getCount() { return count; }
    public void setCount(int ct) { this.count = ct; }

    private long total = -1;
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public List<Solution> getItems() {
        return solutions;
    }
    public int getIndex(String id) {
        for (int i = 0; i < solutions.size(); i++) {
            if (solutions.get(i).getId().equals(id))
                return i;
        }
        return -1;
    }

    public JSONObject getJson() throws JSONException {
        JSONObject json = create();
        json.put("retrieveDate", DateHelper.serviceDateToString(getRetrieveDate()));
        json.put("count", count);
        if (total != -1)
            json.put("total", total);
        JSONArray array = new JSONArray();
        if (solutions != null) {
            for (Solution solution : solutions)
                array.put(solution.getJson());
        }
        json.put("solutions", array);
        return json;
    }

    public String getJsonName() {
        return "Solutions";
    }
}
