package com.sos.jitl.extract.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sos.JSHelper.Basics.JSJobUtilitiesClass;
import com.sos.hibernate.classes.SOSHibernateSession;
import com.sos.hibernate.classes.SOSHibernateFactory;
import com.sos.jitl.extract.model.ResultSet2CSVModel;

public class ResultSet2CSVJob extends JSJobUtilitiesClass<ResultSet2CSVJobOptions> {

    private final String className = ResultSet2CSVJob.class.getSimpleName();
    private static Logger logger = LoggerFactory.getLogger(ResultSet2CSVJob.class);
    private SOSHibernateSession session;

    public ResultSet2CSVJob() {
        super(new ResultSet2CSVJobOptions());
    }

    public void init() throws Exception {
        try {
            SOSHibernateFactory sosHibernateFactory = new SOSHibernateFactory(getOptions().hibernate_configuration_file.getValue());
            sosHibernateFactory.setTransactionIsolation(getOptions().connection_transaction_isolation.value());
            sosHibernateFactory.build();
            session = sosHibernateFactory.openStatelessSession();
        } catch (Exception ex) {
            throw new Exception(String.format("init connection: %s", ex.toString()));
        }
    }

    public void exit() {
        if (session != null) {
            session.close();;
            session.getFactory().close();
        }
    }

    public ResultSet2CSVJob execute() throws Exception {
        final String methodName = className + "::execute";

        logger.debug(methodName);

        try {
            getOptions().checkMandatory();
            logger.debug(getOptions().toString());

            ResultSet2CSVModel model = new ResultSet2CSVModel(session, getOptions());
            model.process();
        } catch (Exception e) {
            logger.error(String.format("%s: %s", methodName, e.toString()));
            throw e;
        }

        return this;
    }

    public ResultSet2CSVJobOptions getOptions() {
        if (objOptions == null) {
            objOptions = new ResultSet2CSVJobOptions();
        }
        return objOptions;
    }

}