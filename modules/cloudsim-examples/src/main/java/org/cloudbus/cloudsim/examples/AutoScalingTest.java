package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.provisioners.*;

import java.text.DecimalFormat;
import java.util.*;

/**
 * A test case for the auto scaling simulation that demonstrates VM scaling with
 * a continuous increasing workload. This simulates a CI/CD environment where we
 * progressively increase the load and verify the VM count scales accordingly.
 */
public class AutoScalingTest {
    // Constants for auto scaling
    private static final double CPU_UPPER_THRESHOLD = 0.7; // 70% CPU utilization - scale up
    private static final double CPU_LOWER_THRESHOLD = 0.3; // 30% CPU utilization - scale down
    private static final double MONITORING_INTERVAL = 10.0; // Time interval for monitoring
    private static final double NEW_CLOUDLET_INTERVAL = 15.0; // Time between adding new cloudlets
    private static final int MIN_VMS = 1; // Minimum number of VMs to maintain

    // Tag for simulation events
    private static final int MONITOR_VM_UTILIZATION = 999991;
    private static final int ADD_CLOUDLET = 999992;

    // Lists to store created objects
    private static List<Vm> vmList;
    private static List<Cloudlet> cloudletList;
    private static int nextCloudletId = 0;
    
    // Counters for statistics
    private static int vmsCreatedCount = 0;
    private static int vmsDestroyedCount = 0;
    private static int cloudletsCreatedCount = 0;
    private static int cloudletsFinishedCount = 0;
    
    // VM scaling stats over time
    private static TreeMap<Double, Integer> vmCountOverTime = new TreeMap<>();

    // Main method
    public static void main(String[] args) {
        Log.printLine("Starting Auto Scaling Test with Increasing Workload...");

        try {
            // Initialize CloudSim
            int numUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceEvents = false;
            CloudSim.init(numUsers, calendar, traceEvents);

            // Create datacenter
            Datacenter datacenter = createDatacenter("Datacenter_0");

            // Create custom broker that implements auto scaling
            AutoScalingBroker broker = new AutoScalingBroker("AutoScalingTestBroker");
            int brokerId = broker.getId();

            // Create initial VMs
            vmList = createVMs(brokerId, 1); // Start with 1 VM
            broker.submitVmList(vmList);

            // Create initial cloudlets
            cloudletList = new ArrayList<>();
            
            // Schedule a task to periodically add more cloudlets
            broker.scheduleAddCloudlet(NEW_CLOUDLET_INTERVAL);

            // Start simulation
            CloudSim.startSimulation();

            // After simulation, print results
            List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();

            // Print results
            Log.printLine("============== AUTO SCALING TEST RESULTS ==============");
            printCloudletList(finishedCloudlets);
            
            Log.printLine("\n============== VM SCALING OVER TIME ==============");
            for (Map.Entry<Double, Integer> entry : vmCountOverTime.entrySet()) {
                Log.formatLine("Time: %.2f - VM Count: %d", entry.getKey(), entry.getValue());
            }
            
            Log.printLine("\n============== SCALING STATISTICS ==============");
            Log.printLine("Initial VMs: 1");
            Log.printLine("Max VMs: " + vmCountOverTime.values().stream().max(Integer::compare).orElse(0));
            Log.printLine("VMs Created: " + vmsCreatedCount);
            Log.printLine("VMs Destroyed: " + vmsDestroyedCount);
            Log.printLine("Final VM Count: " + (1 + vmsCreatedCount - vmsDestroyedCount));
            Log.printLine("Cloudlets Created: " + cloudletsCreatedCount);
            Log.printLine("Cloudlets Finished: " + cloudletsFinishedCount);
            Log.printLine("=================================================");

            Log.printLine("Auto Scaling Test completed!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("The simulation has encountered an error");
        }
    }

    /**
     * Custom broker class that implements auto scaling based on VM utilization
     * and dynamically adds new cloudlets to simulate increasing load
     */
    static class AutoScalingBroker extends DatacenterBroker {
        private Map<Integer, Double> vmUtilization = new HashMap<>();
        private boolean monitoringStarted = false;
        private Random random = new Random(42); // For reproducible results
        
        // Stats collection
        private Map<Double, List<Double>> utilizationHistory = new TreeMap<>();

        public AutoScalingBroker(String name) throws Exception {
            super(name);
        }

        @Override
        public void processEvent(SimEvent ev) {
            if (ev.getTag() == MONITOR_VM_UTILIZATION) {
                monitorAndScale();
                // Record current VM count
                vmCountOverTime.put(CloudSim.clock(), getVmList().size());
                // Schedule next monitoring event
                scheduleMonitoring(MONITORING_INTERVAL);
            } else if (ev.getTag() == ADD_CLOUDLET) {
                addNewCloudlets();
                // Schedule next cloudlet addition
                scheduleAddCloudlet(NEW_CLOUDLET_INTERVAL);
            } else {
                super.processEvent(ev);
            }
        }

        public void scheduleMonitoring(double delay) {
            schedule(getId(), delay, MONITOR_VM_UTILIZATION);
        }
        
        public void scheduleAddCloudlet(double delay) {
            schedule(getId(), delay, ADD_CLOUDLET);
        }

        @Override
        protected void processVmCreate(SimEvent ev) {
            super.processVmCreate(ev);
            
            // Start monitoring when VMs are created
            if (!monitoringStarted) {
                scheduleMonitoring(MONITORING_INTERVAL);
                monitoringStarted = true;
            }
            
            // Increment the counter
            vmsCreatedCount++;
        }

        @Override
        protected void processCloudletReturn(SimEvent ev) {
            Cloudlet cloudlet = (Cloudlet) ev.getData();
            cloudletsFinishedCount++;
            super.processCloudletReturn(ev);
        }
        
        /**
         * Adds new cloudlets to simulate increasing load
         */
        private void addNewCloudlets() {
            // Create multiple cloudlets to increase load
            int cloudletsToAdd = 1 + (int)(CloudSim.clock() / 100); // Increase cloudlet count over time
            Log.formatLine("%.2f: ADDING %d NEW CLOUDLET(S) to simulate increasing load", 
                CloudSim.clock(), cloudletsToAdd);
                
            List<Cloudlet> newCloudlets = createCloudlets(getId(), cloudletsToAdd);
            cloudletList.addAll(newCloudlets);
            
            // Submit new cloudlets to the broker
            submitCloudletList(newCloudlets);
            cloudletsCreatedCount += cloudletsToAdd;
        }

        /**
         * Monitor VM utilization and scale up or down as needed
         */
        private void monitorAndScale() {
            // Get the list of running VMs
            List<Vm> runningVms = new ArrayList<>();
            for (Vm vm : getVmList()) {
                if (vm.getHost() != null) {
                    runningVms.add(vm);
                }
            }
            
            if (runningVms.isEmpty()) {
                return;  // No VMs to monitor
            }
            
            // Calculate average CPU utilization
            double totalUtilization = 0.0;
            int runningCloudletsCount = 0;
            List<Double> currentUtilizations = new ArrayList<>();
            
            for (Vm vm : runningVms) {
                List<Cloudlet> cloudletsOnVm = getCloudletsOnVm(vm.getId());
                runningCloudletsCount += cloudletsOnVm.size();
                
                // Calculate CPU utilization based on number of cloudlets and their lengths
                double totalLength = 0;
                for (Cloudlet c : cloudletsOnVm) {
                    totalLength += c.getRemainingCloudletLength();
                }
                
                // Calculate VM's current utilization based on cloudlet length and VM capacity
                double capacity = vm.getMips() * vm.getNumberOfPes();
                double vmUtil = Math.min(1.0, totalLength / (capacity * MONITORING_INTERVAL));
                
                // Alternatively, use simple approximation based on number of cloudlets
                if (cloudletsOnVm.isEmpty()) {
                    vmUtil = 0.0;
                } else {
                    vmUtil = Math.min(1.0, (double) cloudletsOnVm.size() / 3.0); // Assuming 3 cloudlets per VM is full
                }
                
                vmUtilization.put(vm.getId(), vmUtil);
                totalUtilization += vmUtil;
                currentUtilizations.add(vmUtil);
                
                Log.formatLine("%.2f: VM #%d - CPU Utilization: %.2f, Running Cloudlets: %d", 
                        CloudSim.clock(), vm.getId(), vmUtil, cloudletsOnVm.size());
            }
            
            double avgUtilization = totalUtilization / runningVms.size();
            utilizationHistory.put(CloudSim.clock(), currentUtilizations);
            
            Log.formatLine("%.2f: MONITORING - Average CPU Utilization: %.2f, Running VMs: %d, Active Cloudlets: %d", 
                    CloudSim.clock(), avgUtilization, runningVms.size(), runningCloudletsCount);
            
            // Scale up if average utilization is above upper threshold
            if (avgUtilization > CPU_UPPER_THRESHOLD) {
                // Number of VMs to add is proportional to the utilization overload
                int vmsToAdd = (int)Math.ceil((avgUtilization - CPU_UPPER_THRESHOLD) * 10);
                scaleUp(vmsToAdd);
            } 
            // Scale down if average utilization is below lower threshold and we have more than MIN_VMS VMs
            else if (avgUtilization < CPU_LOWER_THRESHOLD && runningVms.size() > MIN_VMS) {
                // Number of VMs to remove depends on underutilization
                int vmsToRemove = (int)Math.ceil((CPU_LOWER_THRESHOLD - avgUtilization) * runningVms.size());
                vmsToRemove = Math.min(vmsToRemove, runningVms.size() - MIN_VMS);
                if (vmsToRemove > 0) {
                    scaleDown(vmsToRemove);
                }
            }
        }

        /**
         * Scale up by creating new VMs
         */
        private void scaleUp(int count) {
            Log.formatLine("%.2f: SCALING UP - Creating %d new VM(s)", CloudSim.clock(), count);
            
            List<Vm> newVms = createVMs(getId(), count);
            for (Vm vm : newVms) {
                vmList.add(vm);
            }
            
            submitVmList(newVms);
        }

        /**
         * Scale down by destroying least utilized VMs
         */
        private void scaleDown(int count) {
            Log.formatLine("%.2f: SCALING DOWN - Destroying %d VM(s)", CloudSim.clock(), count);
            
            // Find the least utilized VMs
            List<Vm> candidateVms = new ArrayList<>(getVmList());
            candidateVms.sort(Comparator.comparingDouble(vm -> vmUtilization.getOrDefault(vm.getId(), 0.0)));
            
            // Destroy VMs, but maintain the minimum
            int toDestroy = Math.min(count, candidateVms.size() - MIN_VMS);
            for (int i = 0; i < toDestroy; i++) {
                Vm vm = candidateVms.get(i);
                Log.formatLine("%.2f: Destroying VM #%d with utilization %.2f", 
                        CloudSim.clock(), vm.getId(), vmUtilization.getOrDefault(vm.getId(), 0.0));
                
                // Move cloudlets to other VMs before destroying
                List<Cloudlet> cloudletsOnVm = getCloudletsOnVm(vm.getId());
                for (Cloudlet cloudlet : cloudletsOnVm) {
                    // Find another VM to move the cloudlet to
                    for (Vm otherVm : getVmList()) {
                        if (otherVm.getId() != vm.getId() && otherVm.getHost() != null) {
                            // Move cloudlet to another VM
                            cloudlet.setVmId(otherVm.getId());
                            break;
                        }
                    }
                }
                
                // Destroy the VM
                destructVm(vm.getId());
                vmsDestroyedCount++;
            }
        }

        /**
         * Get the list of cloudlets currently running on a VM
         */
        private List<Cloudlet> getCloudletsOnVm(int vmId) {
            List<Cloudlet> result = new ArrayList<>();
            for (Cloudlet cloudlet : getCloudletList()) {
                if (cloudlet.getVmId() == vmId && cloudlet.getCloudletStatus() == Cloudlet.INEXEC) {
                    result.add(cloudlet);
                }
            }
            return result;
        }

        /**
         * Destroy a VM
         */
        private void destructVm(int vmId) {
            sendNow(getDatacenterIdsList().get(0), CloudSimTags.VM_DESTROY, vmList.get(vmId));
        }
    }

    /**
     * Creates the datacenter with a powerful host to support multiple VMs
     */
    private static Datacenter createDatacenter(String name) {
        // List to store machine
        List<Host> hostList = new ArrayList<>();

        // List of PEs (CPUs/Cores)
        List<Pe> peList = new ArrayList<>();

        int mips = 4000; // Higher MIPS for testing
        // Create 8 cores
        for (int i = 0; i < 8; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }

        // Create a powerful host
        int hostId = 0;
        int ram = 32768; // 32 GB
        long storage = 10000000; // 10 TB
        int bw = 100000; // 100 Gbps

        hostList.add(
            new Host(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(bw),
                storage,
                peList,
                new VmSchedulerTimeShared(peList)
            )
        );

        // Create DatacenterCharacteristics
        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double timeZone = 10.0;
        double cost = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.1;
        LinkedList<Storage> storageList = new LinkedList<>();

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, timeZone, cost, costPerMem,
                costPerStorage, costPerBw);

        // Create Datacenter
        Datacenter datacenter = null;
        try {
            datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return datacenter;
    }

    /**
     * Creates a list of VMs.
     */
    private static List<Vm> createVMs(int brokerId, int vmsCount) {
        List<Vm> vms = new ArrayList<>();

        // VM Parameters
        long size = 10000; // image size (MB)
        int ram = 1024; // vm memory (MB)
        int mips = 1000;
        long bw = 1000;
        int pesNumber = 2; // number of cpus per VM
        String vmm = "Xen"; // VMM name

        // Create VMs
        for (int i = 0; i < vmsCount; i++) {
            int vmId = vmList == null ? i : vmList.size() + i;
            Vm vm = new Vm(vmId, brokerId, mips, pesNumber, ram, bw, size, vmm, new CloudletSchedulerTimeShared());
            vms.add(vm);
        }

        return vms;
    }

    /**
     * Creates new cloudlets with variable lengths to simulate different workloads
     */
    private static List<Cloudlet> createCloudlets(int brokerId, int cloudletsCount) {
        List<Cloudlet> list = new ArrayList<>();
        Random random = new Random(System.currentTimeMillis());

        // Cloudlet parameters
        long fileSize = 300;
        long outputSize = 300;
        int pesNumber = 1;
        
        // We use different lengths to simulate different loads
        for (int i = 0; i < cloudletsCount; i++) {
            // Cloudlet length increases over time to simulate growing load
            long baseLength = 20000 + (long)(CloudSim.clock() * 200);
            // Add some randomness
            long length = baseLength + random.nextInt(10000);
            
            UtilizationModel utilizationModel = new UtilizationModelFull();
            Cloudlet cloudlet = new Cloudlet(nextCloudletId++, length, pesNumber, fileSize, outputSize, 
                                          utilizationModel, utilizationModel, utilizationModel);
            cloudlet.setUserId(brokerId);
            
            list.add(cloudlet);
        }

        return list;
    }

    /**
     * Prints the Cloudlet objects.
     */
    private static void printCloudletList(List<Cloudlet> list) {
        int size = list.size();
        Cloudlet cloudlet;

        String indent = "    ";
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + indent + "STATUS" + indent +
                "Data center ID" + indent + "VM ID" + indent + "Time" + indent + "Start Time" + indent + "Finish Time");

        DecimalFormat dft = new DecimalFormat("###.##");
        for (int i = 0; i < size; i++) {
            cloudlet = list.get(i);
            Log.printLine(cloudlet.getCloudletId() + indent + indent + cloudlet.getCloudletStatusString() +
                    indent + indent + cloudlet.getResourceId() + indent + indent + indent + cloudlet.getVmId() +
                    indent + indent + dft.format(cloudlet.getActualCPUTime()) + indent + indent + dft.format(cloudlet.getExecStartTime()) +
                    indent + indent + dft.format(cloudlet.getFinishTime()));
        }
    }
} 