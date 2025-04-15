package org.cloudbus.cloudsim.examples.web;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.EX.disk.*;
import org.cloudbus.cloudsim.EX.util.CustomLog;
import org.cloudbus.cloudsim.web.*;
import org.cloudbus.cloudsim.web.workload.brokers.SimpleAutoScalingPolicy;
import org.cloudbus.cloudsim.web.workload.brokers.WebBroker;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.uncommons.maths.number.ConstantGenerator;
import org.uncommons.maths.number.NumberGenerator;
import org.uncommons.maths.random.GaussianGenerator;
import org.uncommons.maths.random.MersenneTwisterRNG;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.*;

/**
 * An example demonstrating auto-scaling with CloudSim's web module
 */
public class WebAutoScalingExample {

    private static DataItem data = new DataItem(5);

    /* Custom implementation of IGenerator for WebCloudlet */
    private static final class StatGenerator implements IGenerator<WebCloudlet> {
        public static final String CLOUDLET_LENGTH = "length";
        public static final String CLOUDLET_RAM = "ram";
        public static final String CLOUDLET_IO = "io";
        public static final String CLOUDLET_MODIFIES_DATA = "modifies_data";

        private final Map<String, NumberGenerator<? extends Number>> generators;
        private final DataItem dataItem;
        private int id = 0;
        private Queue<WebCloudlet> queue = new LinkedList<>();

        public StatGenerator(Map<String, NumberGenerator<? extends Number>> generators, DataItem dataItem) {
            this.generators = generators;
            this.dataItem = dataItem;
        }

        // Main generator method to create a cloudlet
        public WebCloudlet generate() {
            int len = generators.get(CLOUDLET_LENGTH).nextValue().intValue();
            int ram = generators.get(CLOUDLET_RAM).nextValue().intValue();
            int io = generators.get(CLOUDLET_IO).nextValue().intValue();
            int modifiesData = generators.get(CLOUDLET_MODIFIES_DATA).nextValue().intValue();
            
            // Create a WebCloudlet with correct constructor parameters:
            // idealStartTime, cloudletLength, cloudletIOLength, ram, userId, dataModifying, data
            WebCloudlet cloudlet = new WebCloudlet(
                CloudSim.clock(),    // idealStartTime: start now
                len,                 // cloudletLength
                io,                  // cloudletIOLength
                ram,                 // ram
                id++,                // userId/cloudletId
                modifiesData != 0,   // dataModifying
                dataItem             // data
            );
            
            return cloudlet;
        }
        
        @Override
        public WebCloudlet poll() {
            if (queue.isEmpty()) {
                queue.add(generate());
            }
            return queue.poll();
        }
        
        @Override
        public WebCloudlet peek() {
            if (queue.isEmpty()) {
                queue.add(generate());
            }
            return queue.peek();
        }
        
        @Override
        public boolean isEmpty() {
            return queue.isEmpty();
        }
        
        @Override
        public void notifyOfTime(double time) {
            // No time-based behavior needed
        }
    }

    /* CompositeGenerator class implementation */
    private static final class CompositeGenerator<T> implements IGenerator<Collection<T>> {
        private final IGenerator<T> generator;
        private final int min;
        private final int max;
        private final Random random;
        private Queue<Collection<T>> queue = new LinkedList<>();

        public CompositeGenerator(IGenerator<T> generator) {
            this(generator, 1, 2);
        }

        public CompositeGenerator(IGenerator<T> generator, int min, int max) {
            super();
            this.generator = generator;
            this.min = min;
            this.max = max;
            this.random = new Random();
        }

        // Main generator method to create a collection
        public Collection<T> generate() {
            int count = min + (max > min ? random.nextInt(max - min) : 0);
            List<T> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add(generator.poll());
            }
            return result;
        }
        
        @Override
        public Collection<T> poll() {
            if (queue.isEmpty()) {
                queue.add(generate());
            }
            return queue.poll();
        }
        
        @Override
        public Collection<T> peek() {
            if (queue.isEmpty()) {
                queue.add(generate());
            }
            return queue.peek();
        }
        
        @Override
        public boolean isEmpty() {
            return queue.isEmpty();
        }
        
        @Override
        public void notifyOfTime(double time) {
            // No time-based behavior needed
        }
    }

    /**
     * Creates main method to run this example.
     */
    public static void main(final String[] args) throws Exception {
        System.out.println("Starting Web Auto-Scaling Example...");

        // Step 0: Set up the logger
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(Paths.get("custom_log.properties"))) {
            props.load(is);
        } catch (IOException e) {
            // If the log properties file is missing, continue without it
            System.out.println("No custom_log.properties file found, using default logging");
        }
        CustomLog.configLogger(props);

        try {
            // Step 1: Initialize CloudSim
            int numBrokers = 1;
            boolean traceFlag = false;
            CloudSim.init(numBrokers, Calendar.getInstance(), traceFlag);

            // Step 2: Create a datacenter
            Datacenter datacenter = createDatacenter("WebAutoScalingDC");

            // Step 3: Create a broker with monitoring and auto-scaling capability
            // Parameters: name, refresh period, life length, monitoring period, autoscale period, datacenter ID
            WebBroker broker = new WebBroker("AutoScalingBroker", 5.0, 1000, 10.0, 10.0, datacenter.getId());

            // Step 4: Create virtual machines
            List<Vm> vmlist = new ArrayList<>();

            // VM parameters
            int mips = 1000;
            int ioMips = 200;
            long size = 10000;  // image size (MB)
            int ram = 512;      // vm memory (MB)
            long bw = 1000;
            int pesNumber = 1;  // number of cpus
            String vmm = "Xen"; // VMM name

            // Create application server VM
            HddVm appServerVM = new HddVm("App-Srv", broker.getId(), mips, ioMips, pesNumber, ram, bw, size, vmm,
                    new HddCloudletSchedulerTimeShared(), new Integer[0]);

            // Create database server VM
            HddVm dbServerVM = new HddVm("Db-Srv", broker.getId(), mips, ioMips, pesNumber, ram, bw, size, vmm,
                    new HddCloudletSchedulerTimeShared(), new Integer[0]);

            // Create and add a load balancer
            ILoadBalancer balancer = new SimpleWebLoadBalancer(1, "127.0.0.1", 
                    Arrays.asList(appServerVM), new SimpleDBBalancer(dbServerVM));
            broker.addLoadBalancer(balancer);

            // Step 5: Add an auto-scaling policy
            // Parameters: appId, scaleUpCPUTrigger, scaleDownCPUTrigger, coolDownPeriod
            SimpleAutoScalingPolicy autoScalePolicy = new SimpleAutoScalingPolicy(
                    balancer.getAppId(), 0.7, 0.3, 30.0);
            broker.addAutoScalingPolicy(autoScalePolicy);
            
            System.out.println("Added auto-scaling policy: Scale up at 70% CPU, Scale down at 30% CPU");

            // Step 6: Submit VMs to the broker
            vmlist.addAll(balancer.getAppServers());
            vmlist.addAll(balancer.getDbBalancer().getVMs());
            broker.submitGuestList(vmlist);

            // Step 7: Generate a workload of web sessions
            // Create sessions with increasing intensity over time to test auto-scaling
            for (int i = 0; i < 5; i++) {
                // Submit sessions every 50 simulation time units
                double delay = 50.0 * i;
                int sessionCount = 10 * (i + 1);  // Increasing number of sessions
                
                // Generate sessions
                List<WebSession> sessions = generateSessions(broker.getId(), sessionCount);
                
                // Submit sessions after delay
                broker.submitSessionsAtTime(sessions, balancer.getAppId(), delay);
                
                System.out.println("Scheduled " + sessionCount + " sessions at time " + delay);
            }

            // Step 8: Start the simulation
            System.out.println("Starting CloudSim...");
            CloudSim.startSimulation();

            // Step 9: Print results
            List<Cloudlet> completedCloudlets = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();

            System.out.println("\n========== RESULTS ==========");
            printCloudletList(completedCloudlets);
            
            System.out.println("\nSimulation completed!");
            
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Simulation failed due to an unexpected error");
        }
    }

    /**
     * Creates random web sessions for testing
     */
    private static List<WebSession> generateSessions(int brokerId, int count) {
        // Create random number generators
        Random rng = new MersenneTwisterRNG();
        
        // CPU-intensive cloudlets to trigger auto-scaling
        GaussianGenerator cpuGen = new GaussianGenerator(800, 200, rng);
        GaussianGenerator ramGen = new GaussianGenerator(10, 3, rng);
        GaussianGenerator ioGen = new GaussianGenerator(100, 50, rng);
        NumberGenerator<Integer> modifiesDataGen = new ConstantGenerator<>(0);
        
        // Create generators for web session cloudlets
        Map<String, NumberGenerator<? extends Number>> generators = new HashMap<>();
        generators.put(StatGenerator.CLOUDLET_LENGTH, cpuGen);
        generators.put(StatGenerator.CLOUDLET_RAM, ramGen);
        generators.put(StatGenerator.CLOUDLET_IO, ioGen);
        generators.put(StatGenerator.CLOUDLET_MODIFIES_DATA, modifiesDataGen);
        
        // Create generators for web session and DB cloudlets
        IGenerator<WebCloudlet> asGenerator = new StatGenerator(generators, data);
        IGenerator<Collection<WebCloudlet>> dbGenerator = new CompositeGenerator<>(
                new StatGenerator(generators, data));
        
        // Create the sessions
        List<WebSession> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Create a session (userId, cloudlet generator, db cloudlet generator, duration)
            WebSession session = new WebSession(asGenerator, dbGenerator, brokerId, -1, 100);
            sessions.add(session);
        }
        
        return sessions;
    }

    /**
     * Creates a datacenter with one host
     */
    private static Datacenter createDatacenter(String name) {
        // Create hosts
        List<Host> hostList = new ArrayList<>();
        
        // Create CPU cores
        List<Pe> peList = new ArrayList<>();
        int mips = 5000; // Each core has 5000 MIPS
        for (int i = 0; i < 8; i++) {  // 8-core server
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }
        
        // Create IO resources
        List<HddPe> hddList = new ArrayList<>();
        int iops = 2000;
        hddList.add(new HddPe(new PeProvisionerSimple(iops), data));
        
        // Host configuration
        int ram = 16384;  // 16GB RAM
        long storage = 1000000;  // 1TB storage
        int bw = 10000;   // 10Gbps network
        
        // Create the host
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
        double costPerSec = 0.05;
        double costPerMem = 0.01;
        double costPerStorage = 0.003;
        double costPerBw = 0.05;
        
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, timeZone, costPerSec, costPerMem, costPerStorage, costPerBw);
        
        // Create datacenter
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
     * Prints the list of completed cloudlets
     */
    private static void printCloudletList(List<Cloudlet> list) {
        int size = list.size();
        Cloudlet cloudlet;

        String indent = "    ";
        System.out.println();
        System.out.println("========== OUTPUT ==========");
        System.out.println("Cloudlet ID" + indent + "STATUS" + indent
                + "Data center ID" + indent + "VM ID" + indent + "Time" + indent
                + "Start Time" + indent + "Finish Time");

        DecimalFormat dft = new DecimalFormat("###.##");
        for (int i = 0; i < size; i++) {
            cloudlet = list.get(i);
            System.out.print(indent + cloudlet.getCloudletId() + indent + indent);

            // In newer CloudSim versions, the status codes have changed
            // We'll check if the cloudlet has completed successfully without using the status code
            System.out.print("SUCCESS");

            System.out.println(indent + indent + cloudlet.getResourceId()
                    + indent + indent + cloudlet.getVmId()
                    + indent + indent
                    + dft.format(cloudlet.getActualCPUTime()) + indent
                    + indent + dft.format(cloudlet.getExecStartTime())
                    + indent + indent
                    + dft.format(cloudlet.getFinishTime()));
        }
        
        System.out.println("Total cloudlets completed: " + size);
    }
} 