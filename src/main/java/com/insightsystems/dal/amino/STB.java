package com.insightsystems.dal.amino;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.GenericStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.TelnetCommunicator;

import javax.security.auth.login.FailedLoginException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amino Set Top Box Adapter
 * Company: Insight Systems
 * @author Jayden Loone (@JaydenLInsight)
 * @version 0.5
 *
 * Control:
 *  - Reboot
 *
 * Monitored Statistics:
 * - Kernel Version
 * - MAC Address
 * - CPU Usage
 * - Number Of Processes
 * - Memory Usage
 * - Network Usage In/Out
 */
public class STB extends TelnetCommunicator implements Controller, Monitorable {
    private static final String queryKernelRelease = "uname -r";
    private static final String queryCpuUsage = "top -bn1";
    private static final String queryNetwork = "ifconfig eth0";
    private static final String queryMemory = "cat /proc/meminfo";
    private static final String queryNumProcesses = "ps | wc -l";

    private static final Pattern cpuUsagePattern = Pattern.compile("(\\d+\\.\\d+)%(?:\\s+)?idle");
    private static final Pattern networkRxPattern = Pattern.compile("RX bytes:\\s?(\\d+)");
    private static final Pattern networkTxPattern = Pattern.compile("TX bytes:\\s?(\\d+)");
    private static final Pattern macAddressPattern = Pattern.compile("HWaddr ([\\dA-F:]+)");
    private static final Pattern memoryTotalPattern = Pattern.compile("MemTotal:\\s+(\\d+)");
    private static final Pattern memoryFreePattern = Pattern.compile("MemFree:\\s+(\\d+)");
    private static final Pattern numProcessesPattern = Pattern.compile("(\\d+)");

    private long lastRetrieveTime = 0L;
    private long networkIn,networkOut;

    public STB(){
        this.setLoginPrompt("AMINET login: ");
        this.setPasswordPrompt("Password: ");
        this.setTimeout(10000);
        this.setCommandErrorList(Collections.singletonList(""));
        this.setCommandSuccessList(Collections.singletonList("[root@AMINET]# \n"));
        this.setLoginErrorList(Collections.singletonList("Login incorrect"));
        this.setLoginSuccessList(Collections.singletonList("[root@AMINET]# \n"));
    }

    @Override
    protected boolean doneReadingAfterConnect(String response) throws FailedLoginException {
        if (response.contains("Login incorrect"))
            throw new FailedLoginException("Login Failed: " + response);

        return response.contains("[root@AMINET]") || super.doneReadingAfterConnect(response);
    }

    @Override
    protected boolean doneReading(String command, String response) throws CommandFailureException {
        if (response.contains("not found"))
            throw new CommandFailureException(this.getHost(),command,response);
        return response.contains("[root@AMINET]");
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        Map<String, String> stats = new HashMap<>();
        ExtendedStatistics extStats = new ExtendedStatistics();
        GenericStatistics genStats = new GenericStatistics();
        List<AdvancedControllableProperty> controls = new ArrayList<>();

        try {
            float cpuIdle = Float.parseFloat(regexFind(send(queryCpuUsage), cpuUsagePattern));
            genStats.setCpuPercentage(100.0F - cpuIdle);
        } catch (NumberFormatException e){ //Throw if command fails- Device is unresponsive
            if(this.logger.isWarnEnabled())
                this.logger.warn("Unable to parse number in CPU response.",e);
        }

        try {
            int processes = Integer.parseInt(regexFind(send(queryNumProcesses),numProcessesPattern));
            genStats.setNumberOfProcesses(processes);
        } catch (NumberFormatException e){
            if(this.logger.isWarnEnabled())
                this.logger.warn("Unable to parse integer in processes response.",e);
        }

        String[] kernelSplit = send(queryKernelRelease).split("\r\n");
        if (kernelSplit.length >= 2) {
            stats.put("kernelVersion",kernelSplit[1]);
        } else {
            stats.put("kernelVersion", "Unknown");
        }

        String memoryResponse = send(queryMemory);
        try {
            float totalMemory = Float.parseFloat(regexFind(memoryResponse, memoryTotalPattern));
            float freeMemory = Float.parseFloat(regexFind(memoryResponse, memoryFreePattern));

            genStats.setMemoryTotal(totalMemory / 1048576F); // convert from kb to GB
            genStats.setMemoryInUse((totalMemory - freeMemory) / 1048576F);
        } catch (Exception e) {
            if(this.logger.isWarnEnabled())
                this.logger.warn("Unable to parse number in Memory response.",e);
        }
        String networkResponse = send(queryNetwork);
        if (regexFind(networkResponse,networkRxPattern).isEmpty()){
            networkResponse = send(queryNetwork);
        }
        try {
            stats.put("macAddress", regexFind(networkResponse, macAddressPattern));
            long currentRx = Long.parseLong(regexFind(networkResponse, networkRxPattern));
            long currentTx = Long.parseLong(regexFind(networkResponse, networkTxPattern));


            long now = System.currentTimeMillis();
            if (lastRetrieveTime != 0L) {
                float secondsPassed = (now - lastRetrieveTime)  / 1000F;
                genStats.setNetworkIn ((currentRx - networkIn)  / (1048576F * secondsPassed));
                genStats.setNetworkOut((currentTx - networkOut) / (1048576F * secondsPassed));
            }
            lastRetrieveTime = now;
            networkIn  = currentRx;
            networkOut = currentTx;
        } catch (Exception e){
            if(this.logger.isWarnEnabled())
                this.logger.warn("Unable to parse number in Network response.",e);
        }


        createControls(stats,controls);
        extStats.setControllableProperties(controls);
        extStats.setStatistics(stats);

        return new ArrayList<Statistics>(){{add(extStats);add(genStats);}};
    }

    private void createControls(Map<String,String> stats,List<AdvancedControllableProperty> controls) {
        AdvancedControllableProperty.Button rebootButton = new AdvancedControllableProperty.Button();
        rebootButton.setLabel("Reboot");
        rebootButton.setLabelPressed("Rebooting...");
        rebootButton.setGracePeriod(10000L);
        controls.add(new AdvancedControllableProperty("reboot",new Date(),rebootButton,"0"));
        stats.put("reboot","0");
    }

    @Override
    public void controlProperty(ControllableProperty cp) throws Exception {
        if (cp.getProperty().equals("reboot")) {
            send("reboot");
        } else {
            if (this.logger.isWarnEnabled()) {
                this.logger.warn("Control property " + cp.getProperty() + " is invalid");
            }
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
        for (ControllableProperty cp : list){
            controlProperty(cp);
        }
    }

    /**
     * Find Regular Expression pattern within string
     * @param sourceString String to be searched
     * @param pattern Pattern to search for within string
     * @return First group in regular expression or an empty String
     */
    private String regexFind(String sourceString, Pattern pattern){
        final Matcher matcher = pattern.matcher(sourceString);
        return matcher.find() ? matcher.group(1) : "";
    }
}
