package sos.scheduler.managed.db;

import sos.scheduler.job.JobSchedulerJobAdapter;

public class JobSchedulerManagedDBReportJobJSAdapterClass extends JobSchedulerJobAdapter {

    @Override
    public boolean spooler_process() throws Exception {
        try {
            super.spooler_process();
            doProcessing();
        } catch (Exception e) {
            return false;
        }
        return spooler_task.job().order_queue() != null;
    }

    private void doProcessing() throws Exception {
        JobSchedulerManagedDBReportJob objR = new JobSchedulerManagedDBReportJob();
        JobSchedulerManagedDBReportJobOptions objO = objR.Options();
        objO.setAllOptions(getSchedulerParameterAsProperties(getParameters()));
        objO.checkMandatory();
        objR.setJSJobUtilites(this);
        objR.Execute();
    }

}