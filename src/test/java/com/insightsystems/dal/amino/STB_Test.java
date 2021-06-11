package com.insightsystems.dal.amino;

import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.GenericStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class STB_Test {
    private final String ipAddress = "10.231.64.92";
    private final int port = 23;

    STB amino = new STB();

    @Before
    public void before() throws Exception {
        amino.setHost(ipAddress);
        amino.setPort(port);
        amino.setLogin("root");
        amino.setPassword("root2root");
        amino.init();
    }

    @Test
    public void checkForAllStatistics() throws Exception {
        List<Statistics> statistics = amino.getMultipleStatistics();
        Map<String,String> extendedStatistics = ((ExtendedStatistics) statistics.get(0)).getStatistics();
        GenericStatistics genericStatistics = (GenericStatistics) statistics.get(1);

        Assert.assertNotEquals(0,extendedStatistics.get("kernelVersion").length());
        Assert.assertNotEquals(0,extendedStatistics.get("macAddress").length());
        Assert.assertEquals("0",extendedStatistics.get("reboot"));

        Assert.assertNotNull(genericStatistics.getCpuPercentage());
        Assert.assertNotNull(genericStatistics.getNumberOfProcesses());
        Assert.assertNotNull(genericStatistics.getMemoryInUse());

        //Network Stats are null until 2nd query
        Assert.assertNull(genericStatistics.getNetworkIn());
        Assert.assertNull(genericStatistics.getNetworkOut());

        statistics = amino.getMultipleStatistics();
        genericStatistics = (GenericStatistics) statistics.get(1);

        Assert.assertNotNull(genericStatistics.getNetworkIn());
        Assert.assertNotNull(genericStatistics.getNetworkOut());
    }
}
