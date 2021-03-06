package com.centurylink.mdw.util;

import java.sql.Connection;

import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;

public class TransactionUtil {

    private static TransactionUtil instance;
    private static ThreadLocal<Connection> currentConnection;

    public static TransactionUtil getInstance() {
        if (instance==null) {
            instance = new TransactionUtil();
            currentConnection = new ThreadLocal<Connection>();
        }
        return instance;
    }

    /**
     * Returns the current transaction
     */
    public Transaction getTransaction() {
        try {
            return getTransactionManager().getTransaction();
        }
        catch (Exception ex) {
            StandardLogger logger = LoggerUtil.getStandardLogger();
            logger.error(ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * Returns transaction manager
     */
    public TransactionManager getTransactionManager() {
        TransactionManager transMgr = null;
        try {
            String jndiName = ApplicationContext.getContextProvider().getTransactionManagerName();
            Object txMgr = ApplicationContext.getContextProvider().lookup(null, jndiName, TransactionManager.class);
            transMgr = (TransactionManager)txMgr;
        } catch (Exception ex) {
            StandardLogger logger = LoggerUtil.getStandardLogger();
            logger.error(ex.getMessage(), ex);
        }
        return transMgr;
    }

    public boolean isInTransaction() throws SystemException {
        TransactionManager transManager = getTransactionManager();
        return (transManager.getStatus()==Status.STATUS_ACTIVE);
    }

    public Connection getCurrentConnection() {
        return currentConnection.get();
    }

    public void setCurrentConnection(Connection connection) {
        currentConnection.set(connection);
    }
}
