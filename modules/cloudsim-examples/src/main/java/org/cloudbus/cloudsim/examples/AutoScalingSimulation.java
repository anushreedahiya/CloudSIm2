package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.provisioners.*;

import java.text.DecimalFormat;
import java.util.*;

/**
 * An auto scaling simulation that demonstrates dynamic VM provisioning based on CPU load.
 * The simulation creates a custom broker that monitors VM utilization and adds/removes VMs
 * as needed based on the current load (number of cloudlets or CPU utilization).
 */
public class AutoScalingSimulation {
    // Constants for auto scaling
    private static final double CPU_UPPER_THRESHOLD = 0.8; // 80% CPU utilization - scale up
    private static final double CPU_LOWER_THRESHOLD = 0.2; // 20% CPU utilization - scale down
    private static final double MONITORING_INTERVAL = 5.0; // Time interval for monitoring in simulation time units
    private static final int SCALE_UP_STEP = 1; // Number of VMs to add when scaling up
    private static final int MIN_VMS = 1; // Minimum number of VMs to maintain

    // Custom tags for monitoring events
    private static final int MONITOR_VM_UTILIZATION = 999999;

    // Lists to store created objects
    private static List<Vm> vmList;
    private static List<Cloudlet> cloudletList;
    private static List<Cloudlet> submittedCloudlets = new ArrayList<>();
    private static List<Cloudlet> waitingCloudlets = new ArrayList<>();
    
    // Counters for statistics
    private static int vmsCreatedCount = 0;
    private static int vmsDestroyedCount = 0;
    private static int cloudletsFinishedCount = 0;
    
    // Main method
    public static void main(String[] args) {
        Log.printLine("Starting Auto Scaling Simulation...");

        try {
            // Initialize CloudSim
            int numUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceEvents = false;
            CloudSim.init(numUsers, calendar, traceEvents);

            // Create datacenter
            Datacenter datacenter = createDatacenter("Datacenter_0");

            // Create custom broker that implements auto scaling
            AutoScalingBroker broker = new AutoScalingBroker("AutoScalingBroker");
            int brokerId = broker.getId();

            // Create initial VMs
            vmList = createVMs(brokerId, 1); // Start with 1 VM
            broker.submitVmList(vmList);

            // Create cloudlets with increasing intensity
            cloudletList = createCloudlets(brokerId, 10); // Start with 10 cloudlets
            
            // Keep some cloudlets for later submission
            for (int i = 0; i < cloudletList.size(); i++) {
                if (i < 5) {
                    submittedCloudlets.add(cloudletList.get(i));
                } else {
                    waitingCloudlets.add(cloudletList.get(i));
                }
            }
            
            // Submit initial cloudlets
            broker.submitCloudletList(submittedCloudlets);

            // Start simulation
            CloudSim.startSimulation();

            // After simulation, print results
            List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();

            // Print results
            Log.printLine("============== SIMULATION RESULTS ==============");
            printCloudletList(finishedCloudlets);
            Log.printLine("\n============== SCALING STATISTICS ==============");
            Log.printLine("Initial VMs: 1");
            Log.printLine("VMs Created: " + vmsCreatedCount);
            Log.printLine("VMs Destroyed: " + vmsDestroyedCount);
            Log.printLine("Final VM Count: " + (1 + vmsCreatedCount - vmsDestroyedCount));
            Log.printLine("Cloudlets Finished: " + cloudletsFinishedCount);
            Log.printLine("==============================================");

            Log.printLine("Auto Scaling Simulation completed!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("The simulation has encountered an error");
        }
    }

    /**
     * Custom broker class that implements auto scaling based on VM utilization
     */
    static class AutoScalingBroker extends DatacenterBroker {
        private Map<Integer, Double> vmUtilization = new HashMap<>();
        private int nextVmId = 1; // First VM has ID 0
        private int nextCloudletToSubmit = 0;
        private boolean monitoringStarted = false;

        public AutoScalingBroker(String name) throws Exception {
            super(name);
        }

        @Override
        public void processEvent(SimEvent ev) {
            if (ev.getTag() == MONITOR_VM_UTILIZATION) {
                monitorAndScale();
                // Schedule next monitoring event
                schedule(getId(), MONITORING_INTERVAL, MONITOR_VM_UTILIZATION);
            } else {
                super.processEvent(ev);
            }
        }

        @Override
        protected void processVmCreate(SimEvent ev) {
            super.processVmCreate(ev);
            
            // Start monitoring when VMs are created
            if (!monitoringStarted) {
                schedule(getId(), MONITORING_INTERVAL, MONITOR_VM_UTILIZATION);
                monitoringStarted = true;
            }
            
            // Increment the counter for new VMs
            vmsCreatedCount++;
            
            // Submit more cloudlets if there are still waiting ones
            if (!waitingCloudlets.isEmpty()) {
                // Submit 2 more cloudlets when a new VM is created
                int numToSubmit = Math.min(2, waitingCloudlets.size());
                List<Cloudlet> newCloudlets = new ArrayList<>();
                
                for (int i = 0; i < numToSubmit; i++) {
                    newCloudlets.add(waitingCloudlets.remove(0));
                }
                
                submitCloudletList(newCloudlets);
            }
        }

        @Override
        protected void processCloudletReturn(SimEvent ev) {
            Cloudlet cloudlet = (Cloudlet) ev.getData();
            cloudletsFinishedCount++;
            
            // Call the parent method to process the returned cloudlet
            super.processCloudletReturn(ev);
            
            // If there are more cloudlets to submit, submit them
            if (!waitingCloudlets.isEmpty()) {
                Cloudlet nextCloudlet = waitingCloudlets.remove(0);
                submitCloudletList(Arrays.asList(nextCloudlet));
            }
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
            
            for (Vm vm : runningVms) {
                List<Cloudlet> cloudletsOnVm = getCloudletsOnVm(vm.getId());
                runningCloudletsCount += cloudletsOnVm.size();
                
                // Calculate approximate CPU utilization based on number of cloudlets
                double vmUtil = Math.min(1.0, (double) cloudletsOnVm.size() / 2.0); // Assuming 2 cloudlets per VM is full
                vmUtilization.put(vm.getId(), vmUtil);
                totalUtilization += vmUtil;
                
                Log.formatLine("%.2f: VM #%d - CPU Utilization: %.2f, Running Cloudlets: %d", 
                        CloudSim.clock(), vm.getId(), vmUtil, cloudletsOnVm.size());
            }
            
            double avgUtilization = totalUtilization / runningVms.size();
            Log.formatLine("%.2f: MONITORING - Average CPU Utilization: %.2f, Running VMs: %d, Active Cloudlets: %d", 
                    CloudSim.clock(), avgUtilization, runningVms.size(), runningCloudletsCount);
            
            // Scale up if average utilization is above upper threshold
            if (avgUtilization > CPU_UPPER_THRESHOLD) {
                scaleUp(SCALE_UP_STEP);
            } 
            // Scale down if average utilization is below lower threshold and we have more than MIN_VMS VMs
            else if (avgUtilization < CPU_LOWER_THRESHOLD && runningVms.size() > MIN_VMS) {
                scaleDown(1);
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
            // Get cloudlets running on the VM
            List<Cloudlet> cloudletsOnVm = getCloudletsOnVm(vmId);
            
            // Move cloudlets to other VMs or back to waiting queue
            for (Cloudlet cloudlet : cloudletsOnVm) {
                // Find another VM to move the cloudlet to
                boolean moved = false;
                for (Vm vm : getVmList()) {
                    if (vm.getId() != vmId && vm.getHost() != null) {
                        // Move cloudlet to another VM
                        cloudlet.setVmId(vm.getId());
                        moved = true;
                        break;
                    }
                }
                
                // If couldn't move, add back to waiting cloudlets
                if (!moved) {
                    waitingCloudlets.add(cloudlet);
                }
            }
            
            // Send VM destroy request
            sendNow(getDatacenterIdsList().get(0), CloudSimTags.VM_DESTROY, vmList.get(vmId));
        }
    }

    /**
     * Creates the datacenter.
     */
    private static Datacenter createDatacenter(String name) {
        // List to store machine
        List<Host> hostList = new ArrayList<>();

        // List of PEs (CPUs/Cores)
        List<Pe> peList = new ArrayList<>();

        int mips = 1000;
        peList.add(new Pe(0, new PeProvisionerSimple(mips)));
        peList.add(new Pe(1, new PeProvisionerSimple(mips)));
        peList.add(new Pe(2, new PeProvisionerSimple(mips)));
        peList.add(new Pe(3, new PeProvisionerSimple(mips)));

        // Create Host with its id and list of PEs
        int hostId = 0;
        int ram = 8192; // 8 GB
        long storage = 1000000; // 1 TB
        int bw = 10000; // 10 Gbps

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
        int ram = 512; // vm memory (MB)
        int mips = 1000;
        long bw = 1000;
        int pesNumber = 1; // number of cpus
        String vmm = "Xen"; // VMM name

        // Create VMs
        for (int i = 0; i < vmsCount; i++) {
            Vm vm = new Vm(vmList == null ? i : vmList.size() + i, brokerId, mips, pesNumber, ram, bw, size, vmm, new CloudletSchedulerTimeShared());
            vms.add(vm);
        }

        return vms;
    }

    /**
     * Creates a list of Cloudlets.
     */
    private static List<Cloudlet> createCloudlets(int brokerId, int cloudletsCount) {
        List<Cloudlet> list = new ArrayList<>();

        // Cloudlet parameters
        long fileSize = 300;
        long outputSize = 300;
        int pesNumber = 1;
        
        // We use different lengths to simulate different loads
        for (int i = 0; i < cloudletsCount; i++) {
            long length = 10000 + (i * 2000); // Increasing lengths for later cloudlets
            
            UtilizationModel utilizationModel = new UtilizationModelFull();
            Cloudlet cloudlet = new Cloudlet(i, length, pesNumber, fileSize, outputSize, utilizationModel, utilizationModel, utilizationModel);
            cloudlet.setUserId(brokerId);
            
            // Initially, not assigned to any VM - will be assigned by broker
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