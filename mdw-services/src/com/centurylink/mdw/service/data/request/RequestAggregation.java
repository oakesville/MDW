package com.centurylink.mdw.service.data.request;

import com.centurylink.mdw.common.service.Query;
import com.centurylink.mdw.common.service.ServiceException;
import com.centurylink.mdw.constant.OwnerType;
import com.centurylink.mdw.dataaccess.DataAccessException;
import com.centurylink.mdw.dataaccess.PreparedSelect;
import com.centurylink.mdw.dataaccess.PreparedWhere;
import com.centurylink.mdw.dataaccess.reports.AggregateDataAccess;
import com.centurylink.mdw.model.request.RequestAggregate;
import com.centurylink.mdw.model.request.ServicePath;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

public class RequestAggregation extends AggregateDataAccess<RequestAggregate> {

    @Override
    public List<RequestAggregate> getTops(Query query) throws DataAccessException, ServiceException {
        String by = query.getFilter("by");
        if (by == null)
            throw new ServiceException(ServiceException.BAD_REQUEST, "Missing required filter: 'by'");
        try {
            db.openConnection();
            if (by.equals("throughput"))
                return getTopsByThroughput(query);
            else if (by.equals("status"))
                return getTopsByStatus(query);
            else if (by.equals("completionTime"))
                return getTopsByCompletionTime(query);
            else
                throw new ServiceException(ServiceException.BAD_REQUEST, "Unsupported filter: by=" + by);
        }
        catch (SQLException ex) {
            throw new DataAccessException(ex.getMessage(), ex);
        }
        finally {
            db.closeConnection();
        }
    }

    private List<RequestAggregate> getTopsByThroughput(Query query)
            throws DataAccessException, SQLException, ServiceException {
        PreparedWhere preparedWhere = getRequestWhere(query);
        String sql = db.pagingQueryPrefix() +
                "select path, count(path) as ct\n" +
                "from DOCUMENT doc\n" +
                preparedWhere.getWhere() +
                "group by path\n" +
                "order by ct desc\n" +
                db.pagingQuerySuffix(query.getStart(), query.getMax());
        PreparedSelect preparedSelect = new PreparedSelect(sql, preparedWhere.getParams(),
                "RequestAggregation.getTopsByThroughput()");

        return getTopAggregates(preparedSelect, query, resultSet -> {
            long ct = Math.round(resultSet.getDouble("ct"));
            RequestAggregate requestAggregate = new RequestAggregate(ct);
            requestAggregate.setCount(ct);
            requestAggregate.setPath(resultSet.getString("path"));
            return requestAggregate;
        });
    }

    private List<RequestAggregate> getTopsByStatus(Query query)
            throws DataAccessException, SQLException, ServiceException {
        PreparedWhere preparedWhere = getRequestWhere(query);
        String sql = db.pagingQueryPrefix() +
                "select status_code, count(status_code) as ct from DOCUMENT\n" +
                preparedWhere.getWhere() +
                "group by status_code\n" +
                "order by ct desc\n" +
                db.pagingQuerySuffix(query.getStart(), query.getMax());
        PreparedSelect preparedSelect = new PreparedSelect(sql, preparedWhere.getParams(),
                "RequestAggregation.getTopsByStatus()");
        return getTopAggregates(preparedSelect, query, resultSet -> {
            long ct = Math.round(resultSet.getDouble("ct"));
            RequestAggregate requestAggregate = new RequestAggregate(ct);
            requestAggregate.setCount(ct);
            Integer statusCode = resultSet.getInt("status_code");
            requestAggregate.setId(statusCode);
            requestAggregate.setStatus(statusCode);
            return requestAggregate;
        });
    }

    private List<RequestAggregate> getTopsByCompletionTime(Query query)
            throws DataAccessException, SQLException, ServiceException {
        PreparedWhere preparedWhere = getRequestWhere(query);
        String sql = db.pagingQueryPrefix() +
                "select path, avg(elapsed_ms) as elapsed, count(path) as ct\n" +
                "from DOCUMENT" +
                ", INSTANCE_TIMING it\n" +
                preparedWhere.getWhere() +
                "group by path\n" +
                "order by elapsed desc\n" +
                db.pagingQuerySuffix(query.getStart(), query.getMax());
        PreparedSelect preparedSelect = new PreparedSelect(sql, preparedWhere.getParams(),
                "RequestAggregation.getTopsByCompletionTime()");
        return getTopAggregates(preparedSelect, query, resultSet -> {
            Long elapsed = Math.round(resultSet.getDouble("elapsed"));
            RequestAggregate requestAggregate = new RequestAggregate(elapsed);
            requestAggregate.setCount(resultSet.getLong("ct"));
            requestAggregate.setPath(resultSet.getString("path"));
            return requestAggregate;
        });
    }

    public TreeMap<Instant,List<RequestAggregate>> getBreakdown(Query query) throws DataAccessException, ServiceException {
        String by = query.getFilter("by");
        if (by == null)
            throw new ServiceException(ServiceException.BAD_REQUEST, "Missing required filter: 'by'");
        try {
            PreparedWhere preparedWhere = getRequestWhere(query);
            StringBuilder sql = new StringBuilder();
            if (by.equals("status"))
                sql.append("select count(req.status_code) as val, req.st, req.status_code\n");
            else if (by.equals("throughput"))
                sql.append("select count(req.path) as val, req.st, req.path\n");
            else if (by.equals("completionTime"))
                sql.append("select avg(req.elapsed_ms) as val, req.st, req.path\n");
            else if (by.equals("total"))
                sql.append("select count(req.st) as val, req.st\n");

            sql.append("from (select ").append(getSt("create_dt", query));

            if (by.equals("status"))
                sql.append(", status_code ");
            else if (!by.equals("total"))
                sql.append(", path ");
            if (by.equals("completionTime"))
                sql.append(", elapsed_ms");
            sql.append("\n  from DOCUMENT");
            if (by.equals("completionTime"))
                sql.append(", INSTANCE_TIMING it");
            sql.append("\n  ");
            sql.append(preparedWhere.getWhere()).append(" ");
            List<Object> params = new ArrayList<>(Arrays.asList(preparedWhere.getParams()));
            if (by.equals("status")) {
                String[] statuses = query.getArrayFilter("statusCodes");
                List<Integer> statusCodes = null;
                if (statuses != null) {
                    statusCodes = new ArrayList<>();
                    for (String status : statuses)
                        statusCodes.add(Integer.parseInt(status));
                }
                PreparedWhere inCondition = getInCondition(statusCodes);
                sql.append("   and status_code ").append(inCondition.getWhere());
                params.addAll(Arrays.asList(inCondition.getParams()));
            }
            else if (!by.equals("total")) {
                String[] requestPathsArr = query.getArrayFilter("requestPaths");
                List<String> requestPaths = requestPathsArr == null ? null : Arrays.asList(requestPathsArr);
                PreparedWhere inCondition = getInCondition(requestPaths);
                sql.append("   and path ").append(inCondition.getWhere());
                params.addAll(Arrays.asList(inCondition.getParams()));
            }
            sql.append(") req\n");

            sql.append("group by st");
            if (by.equals("status"))
                sql.append(", status_code");
            else if (!by.equals("total"))
                sql.append(", path");


            PreparedSelect select = new PreparedSelect(sql.toString(), params.toArray(), "Breakdown by " + by);

            return handleBreakdownResult(select, query, rs -> {
                RequestAggregate requestAggregate = new RequestAggregate(rs.getLong("val"));
                if (by.equals("status")) {
                    int statusCode = rs.getInt("status_code");
                    requestAggregate.setStatus(statusCode);
                    requestAggregate.setId(statusCode);
                }
                else if (!by.equals("total")) {
                    requestAggregate.setPath(rs.getString("path"));
                }
                return requestAggregate;
            });
        }
        catch (DataAccessException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new DataAccessException(ex.getMessage(), ex);
        }
    }

    public List<ServicePath> getPaths(Query query) throws DataAccessException, ServiceException {

        try {
            db.openConnection();
            PreparedWhere preparedWhere = getRequestWhere(query);
            // on mysql group by is much faster than select distinct
            String sql = db.pagingQueryPrefix() +
                    (db.isMySQL() ? "select path " : "select distinct path ") +
                    "from DOCUMENT doc\n" +
                    preparedWhere.getWhere() + (db.isMySQL() ? "group by path " : "") +
                    "order by path\n" +
                    db.pagingQuerySuffix(query.getStart(), query.getMax());
            ResultSet rs = db.runSelect(new PreparedSelect(sql, preparedWhere.getParams()));
            List<ServicePath> servicePaths = new ArrayList<>();
            while (rs.next()) {
                servicePaths.add(new ServicePath(rs.getString(1)));
            }
            return servicePaths;
        }
        catch (SQLException ex) {
            throw new DataAccessException(ex.getMessage(), ex);
        }
        finally {
            db.closeConnection();
        }
    }

    protected PreparedWhere getRequestWhere(Query query) throws DataAccessException {
        String by = query.getFilter("by");
        Instant start = getStart(query);

        StringBuilder where = new StringBuilder("where path is not null\n");
        List<Object> params = new ArrayList<>();

        String ownerType;
        String direction = query.getFilter("direction");
        if ("out".equals(direction)) {
            if ("completionTime".equals(by))
                ownerType = OwnerType.ADAPTER;
            else
                ownerType = OwnerType.ADAPTER_RESPONSE;
        }
        else {
            ownerType = OwnerType.LISTENER_RESPONSE;
        }

        if ("completionTime".equals(by)) {
            if ("out".equals(direction))
                where.append("  and instance_id = owner_id and it.owner_type = ?\n");
            else
                where.append("  and instance_id = document_id and it.owner_type = ?\n");
            params.add(ownerType);
        }
        else {
            where.append("  and owner_type = ?\n");
            params.add(ownerType);
        }

        where.append("  and create_dt >= ? ");
        params.add(getDbDt(start));

        Instant end = getEnd(query);
        if (end != null) {
            where.append(" and create_dt <= ? ");
            params.add(getDbDt(end));
        }
        where.append("\n");

        String status = query.getFilter("Status");
        if (status != null) {
            int spaceHyphen = status.indexOf(" -");
            if (spaceHyphen > 0)
                status = status.substring(0,spaceHyphen);
            where.append("  and status_code = ?\n");
            params.add(Integer.parseInt(status));
        }

        String find = query.getFind();
        if (find != null) {
            if (find.startsWith("/"))
                find = find.substring(1);
            where.append("  and (path like '" + find + "%' " +
                "or path like 'GET->" + find + "%' " +
                "or path like 'POST->" + find + "%' " +
                "or path like 'PUT->" + find + "%' " +
                "or path like 'DELETE->" + find + "%' " +
                "or path like 'PATCH->" + find + "%')\n");
        }

        return new PreparedWhere(where.toString(), params.toArray());
    }
}
