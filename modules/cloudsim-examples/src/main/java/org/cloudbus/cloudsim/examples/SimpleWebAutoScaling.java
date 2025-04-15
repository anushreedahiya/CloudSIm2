package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.EX.IAutoscalingPolicy;
import org.cloudbus.cloudsim.EX.MonitoringBrokerEX;
import org.cloudbus.cloudsim.EX.disk.*;
import org.cloudbus.cloudsim.EX.util.CustomLog;
import org.cloudbus.cloudsim.EX.vm.VmStatus;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.web.*;
import org.cloudbus.cloudsim.web.workload.brokers.WebBroker;

import java.util.*;

/**
 * A simplified example demonstrating auto-scaling in CloudSim
 */
public class SimpleWebAutoScaling {

    /**
     * Creates main method to run this example
     */
    public static void main(String[] args) {
        try {
            System.out.println("Starting Simple Web Auto-Scaling Example...");

            // Initialize CloudSim
            int numUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;
            CloudSim.init(numUsers, calendar, traceFlag);

            // Create a datacenter
            Datacenter datacenter = createDatacenter("DatacenterSimple");

            // Create a broker that handles auto-scaling
            WebBroker broker = new WebBroker("AutoScaleBroker", 5.0, 1000, 10.0, 10.0, datacenter.getId());

            // Create application server VMs
            int mips = 1000;
            int ioMips = 200;
            int ram = 512;
            long size = 10000;
            long bw = 1000;
            int pesNumber = 1;
            String vmm = "Xen";
            
            HddVm appServerVM = new HddVm("AppSrv1", broker.getId(), mips, ioMips, pesNumber, ram, bw, size, vmm,
                    new HddCloudletSchedulerTimeShared(), new Integer[0]);
            
            HddVm dbServerVM = new HddVm("DbSrv", broker.getId(), mips, ioMips, pesNumber, ram, bw, size, vmm,
                    new HddCloudletSchedulerTimeShared(), new Integer[0]);

            // Create a load balancer
            ILoadBalancer balancer = new SimpleWebLoadBalancer(1, "127.0.0.1", 
                    Arrays.asList(appServerVM), new SimpleDBBalancer(dbServerVM));
            broker.addLoadBalancer(balancer);

            // Create and add the auto-scaling policy
            SimpleAutoScalingPolicy autoScalePolicy = new SimpleAutoScalingPolicy(
                    balancer.getAppId(), 0.7, 0.3, 30.0);
            broker.addAutoScalingPolicy(autoScalePolicy);
            
            System.out.println("Added auto-scaling policy: Scale up at 70% CPU, Scale down at 30% CPU");

            // Submit VMs to the broker
            List<Vm> vmList = new ArrayList<>();
            vmList.addAll(balancer.getAppServers());
            vmList.addAll(balancer.getDbBalancer().getVMs());
            broker.submitGuestList(vmList);

            // Submit cloudlets as web sessions
            for (int i = 0; i < 10; i++) {
                double delay = 10.0 * i;
                int cloudletsCount = 5 * (i + 1);
                
                System.out.println("Creating " + cloudletsCount + " web sessions at time " + delay);
                
                List<WebSession> sessions = createSessions(broker.getId(), cloudletsCount);
                broker.submitSessionsAtTime(sessions, balancer.getAppId(), delay);
            }

            // Start the simulation
            System.out.println("Starting CloudSim...");
            CloudSim.startSimulation();
            
            // Print results
            List<Cloudlet> completedCloudlets = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            
            System.out.println("\nSimulation completed!");
            System.out.println("Total cloudlets completed: " + completedCloudlets.size());
            
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("The simulation had problems to run!");
        }
    }
    
    /**
     * Creates a simple datacenter
     */
    private static Datacenter createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        
        // Create CPU cores
        List<Pe> peList = new ArrayList<>();
        int mips = 5000;
        for (int i = 0; i < 4; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }
        
        // Create a disk I/O resource
        DataItem data = new DataItem(5);
        List<HddPe> hddList = new ArrayList<>();
        int iops = 1000;
        hddList.add(new HddPe(new PeProvisionerSimple(iops), data));
        
        // Create a host
        int ram = 8192;
        long storage = 1000000;
        int bw = 10000;
        
        hostList.add(new HddHost(
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(bw),
                storage,
                peList,
                hddList,
                new VmSchedulerTimeShared(peList),
                new VmDiskScheduler(hddList)
        ));
        
        // Create datacenter characteristics
        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double timeZone = 10.0;
        double cost = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.0;
        
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, timeZone, cost, costPerMem, costPerStorage, costPerBw);
        
        // Create Datacenter
        Datacenter datacenter = null;
        try {
            datacenter = new Datacenter(name, characteristics, 
                    new VmAllocationPolicySimple(hostList), new LinkedList<Storage>(), 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return datacenter;
    }
    
    /**
     * A simple auto-scaling policy that scales based on CPU utilization
     */
    private static class SimpleAutoScalingPolicy implements IAutoscalingPolicy {
        private final double scaleUpCPUTrigger;
        private final double scaleDownCPUTrigger;
        private final double coolDownPeriod;
        private final long appId;
        private double lastActionTime = -1;

        public SimpleAutoScalingPolicy(long appId, double scaleUpCPUTrigger, double scaleDownCPUTrigger, double coolDownPeriod) {
            this.appId = appId;
            this.scaleUpCPUTrigger = scaleUpCPUTrigger;
            this.scaleDownCPUTrigger = scaleDownCPUTrigger;
            this.coolDownPeriod = coolDownPeriod;
        }

        @Override
        public void scale(MonitoringBrokerEX broker) {
            double currentTime = CloudSim.clock();
            boolean performScaling = lastActionTime < 0 || lastActionTime + coolDownPeriod < currentTime;

            if (performScaling && broker instanceof WebBroker webBroker) {
                ILoadBalancer loadBalancer = webBroker.getLoadBalancers().get(appId);

                double avgCPU = 0;
                int count = 0;
                HddVm candidateToStop = null;
                
                for (HddVm vm : loadBalancer.getAppServers()) {
                    if (!EnumSet.of(VmStatus.INITIALISING, VmStatus.RUNNING).contains(vm.getStatus())) {
                        continue;
                    }
                    avgCPU += vm.getCPUUtil();
                    count++;
                    candidateToStop = vm;
                }
                
                avgCPU = count == 0 ? 0 : avgCPU / count;
                
                System.out.println("Auto-scaling check at " + currentTime + ": Average CPU = " + avgCPU);

                if (avgCPU > scaleUpCPUTrigger) {
                    // Scale up by adding a new VM
                    HddVm newVM = loadBalancer.getAppServers().get(0).clone(new HddCloudletSchedulerTimeShared());
                    loadBalancer.registerAppServer(newVM);
                    webBroker.createVmsAfter(List.of(newVM), 0);
                    lastActionTime = currentTime;
                    
                    System.out.println("SCALING UP: Added new VM at time " + currentTime);
                    
                } else if (avgCPU < scaleDownCPUTrigger && count > 1) {
                    // Scale down by removing a VM
                    List<HddVm> toStop = List.of(candidateToStop);
                    webBroker.destroyVMsAfter(toStop, 0);
                    loadBalancer.getAppServers().removeAll(toStop);
                    lastActionTime = currentTime;
                    
                    System.out.println("SCALING DOWN: Removed VM at time " + currentTime);
                }
            }
        }
    }
    
    /**
     * Create web sessions
     */
    private static List<WebSession> createSessions(int brokerId, int count) {
        List<WebSession> sessions = new ArrayList<>();
        
        // Create a simple data item
        DataItem data = new DataItem(5);
        
        // Create some sample web sessions that will generate load
        for (int i = 0; i < count; i++) {
            // Create a simple IGenerator for web cloudlets
            IGenerator<WebCloudlet> asCloudletGen = new SimpleCloudletGenerator(data);
            IGenerator<Collection<WebCloudlet>> dbCloudletGen = new SimpleDbCloudletGenerator(data);
            
            WebSession session = new WebSession(asCloudletGen, dbCloudletGen, brokerId, -1, 100);
            sessions.add(session);
        }
        
        return sessions;
    }
    
    /**
     * Simple generator for application server cloudlets
     */
    private static class SimpleCloudletGenerator implements IGenerator<WebCloudlet> {
        private int id = 0;
        private final DataItem data;
        
        public SimpleCloudletGenerator(DataItem data) {
            this.data = data;
        }
        
        @Override
        public WebCloudlet poll() {
            // Create a cloudlet with a simulated workload
            // Parameters: idealStartTime, cloudletLength, cloudletIOLength, ram, userId, dataModifying, data
            Random rand = new Random();
            long length = 500 + rand.nextInt(1000); // CPU length between 500-1500
            long ioLength = 100 + rand.nextInt(200); // IO length between 100-300
            double ram = 10 + rand.nextInt(20);      // RAM between 10-30
            
            WebCloudlet cloudlet = new WebCloudlet(
                CloudSim.clock(),  // Start now
                length,            // CPU workload
                ioLength,          // IO workload
                ram,               // RAM usage
                id++,              // Cloudlet ID
                false,             // Doesn't modify data
                data               // Reference to data
            );
            
            return cloudlet;
        }
        
        @Override
        public WebCloudlet peek() {
            // We don't implement real peeking in this simple example
            return poll();
        }
        
        @Override
        public boolean isEmpty() {
            return false;
        }
        
        @Override
        public void notifyOfTime(double time) {
            // Not implemented in this simple example
        }
    }
    
    /**
     * Simple generator for database cloudlets
     */
    private static class SimpleDbCloudletGenerator implements IGenerator<Collection<WebCloudlet>> {
        private final DataItem data;
        private int id = 1000; // Start DB cloudlet IDs from 1000
        
        public SimpleDbCloudletGenerator(DataItem data) {
            this.data = data;
        }
        
        @Override
        public Collection<WebCloudlet> poll() {
            List<WebCloudlet> dbCloudlets = new ArrayList<>();
            
            // Create 1-3 DB cloudlets for each web session
            Random rand = new Random();
            int numCloudlets = 1 + rand.nextInt(3);
            
            for (int i = 0; i < numCloudlets; i++) {
                long length = 200 + rand.nextInt(300);   // CPU length between 200-500
                long ioLength = 300 + rand.nextInt(500); // IO length between 300-800 (more IO intensive)
                double ram = 5 + rand.nextInt(10);       // RAM between 5-15
                
                WebCloudlet cloudlet = new WebCloudlet(
                    CloudSim.clock(),  // Start now
                    length,            // CPU workload
                    ioLength,          // IO workload
                    ram,               // RAM usage
                    id++,              // Cloudlet ID
                    true,              // Modifies data
                    data               // Reference to data
                );
                
                dbCloudlets.add(cloudlet);
            }
            
            return dbCloudlets;
        }
        
        @Override
        public Collection<WebCloudlet> peek() {
            // We don't implement real peeking in this simple example
            return poll();
        }
        
        @Override
        public boolean isEmpty() {
            return false;
        }
        
        @Override
        public void notifyOfTime(double time) {
            // Not implemented in this simple example
        }
    }
} 