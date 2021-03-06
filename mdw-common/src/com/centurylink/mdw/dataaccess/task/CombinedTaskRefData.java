package com.centurylink.mdw.dataaccess.task;

import com.centurylink.mdw.model.task.TaskCategory;
import com.centurylink.mdw.model.task.TaskState;
import com.centurylink.mdw.model.task.TaskStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supports multiple injected TaskRefData impls.
 */
public class CombinedTaskRefData implements TaskRefData {

    private Map<Integer,String> taskCategoryCodes = new HashMap<>();;
    private Map<Integer,TaskCategory> taskCategories = new HashMap<>();

    private Map<Integer,TaskState> taskStates = new HashMap<>();
    private List<TaskState> allTaskStates = new ArrayList<>();

    private Map<Integer,TaskStatus> taskStatuses = new HashMap<>();
    private List<TaskStatus> allTaskStatuses = new ArrayList<>();


    public CombinedTaskRefData(List<TaskRefData> taskRefDatas) {
        for (TaskRefData taskRefData : taskRefDatas) {
          for (int categoryId : taskRefData.getCategoryCodes().keySet()) {
              if (!taskCategoryCodes.containsKey(categoryId))
                  taskCategoryCodes.put(categoryId, taskRefData.getCategoryCodes().get(categoryId));
          }
          for (int categoryId : taskRefData.getCategories().keySet()) {
              if (!taskCategories.containsKey(categoryId))
                  taskCategories.put(categoryId, taskRefData.getCategories().get(categoryId));
          }
          for (int taskStateId : taskRefData.getStates().keySet()) {
              if (!taskStates.containsKey(taskStateId)) {
                  TaskState taskState = taskRefData.getStates().get(taskStateId);
                  taskStates.put(taskStateId, taskState);
                  allTaskStates.add(taskState);
              }
          }
          for (int taskStatusId : taskRefData.getStatuses().keySet()) {
              if (!taskStatuses.containsKey(taskStatusId)) {
                  TaskStatus taskStatus = taskRefData.getStatuses().get(taskStatusId);
                  taskStatuses.put(taskStatusId, taskStatus);
                  allTaskStatuses.add(taskStatus);
              }
          }
        }
    }

    @Override
    public Map<Integer,String> getCategoryCodes() {
        return taskCategoryCodes;
    }

    @Override
    public Map<Integer,TaskCategory> getCategories() {
        return taskCategories;
    }

    @Override
    public Map<Integer,TaskState> getStates() {
        return taskStates;
    }

    @Override
    public List<TaskState> getAllStates() {
        return allTaskStates;
    }

    @Override
    public Map<Integer,TaskStatus> getStatuses() {
        return taskStatuses;
    }

    @Override
    public List<TaskStatus> getAllStatuses() {
        return allTaskStatuses;
    }
}
