package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.text.DecimalFormat;
import java.util.*;

/**
 * A basic auto-scaling example showing how to create a datacenter with auto-scaling functionality
 */
public class BasicAutoScalingExample {

    // Auto-scaling thresholds
    private static final double CPU_UPPER_THRESHOLD = 0.8; // 80% utilization to scale up
    private static final double CPU_LOWER_THRESHOLD = 0.2; // 20% utilization to scale down
    
    // Custom tag for VM monitoring (chosen to avoid conflicts with existing tags)
    private static final int MONITOR_VM_TAG = CloudSimTags.VM_CREATE_ACK + 999;
    
    // VM monitoring interval
    private static final double MONITORING_INTERVAL = 5.0;
    
    // Counters for VMs
    private static int totalVmsCreated = 0;
    private static int totalVmsDestroyed = 0;

    /**
     * The main method to run this example
     */
    public static void main(String[] args) {
        Log.printLine("Starting BasicAutoScalingExample...");

        try {
            // Initialize CloudSim
            int numUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;
            CloudSim.init(numUsers, calendar, traceFlag);

            // Create datacenter
            @SuppressWarnings("unused")
            Datacenter datacenter0 = createDatacenter("Datacenter_0");

            // Create auto-scaling broker
            MyAutoScalingBroker broker = createBroker();
            int brokerId = broker.getId();

            // Create VM list
            List<Vm> vmList = new ArrayList<>();
            
            // VM parameters
            int vmId = 0;
            int mips = 1000;
            long size = 10000; // image size (MB)
            int ram = 512; // VM memory (MB)
            long bw = 1000;
            int pesNumber = 1; // number of CPUs
            String vmm = "Xen"; // VMM name

            // Create initial VM
            Vm vm = new Vm(vmId, brokerId, mips, pesNumber, ram, bw, size, vmm, new CloudletSchedulerTimeShared());
            vmList.add(vm);
            totalVmsCreated++;

            // Submit VM list to the broker
            broker.submitGuestList(vmList);

            // Create cloudlets
            List<Cloudlet> cloudletList = new ArrayList<>();
            
            // Cloudlet parameters
            int id = 0;
            long length = 50000; // in MI
            long fileSize = 300; // in bytes
            long outputSize = 300; // in bytes
            
            // Create 15 cloudlets with increasing utilization requirements
            for (int i = 0; i < 15; i++) {
                // Increase length for each cloudlet to simulate increasing load
                Cloudlet cloudlet = new Cloudlet(
                    id++, 
                    length * (1 + i/3), // Increasing length
                    pesNumber, 
                    fileSize, 
                    outputSize, 
                    new UtilizationModelStochastic(), // Variable CPU utilization
                    new UtilizationModelFull(), // Full RAM utilization
                    new UtilizationModelFull() // Full BW utilization
                );
                cloudlet.setUserId(brokerId);
                cloudletList.add(cloudlet);
            }

            // Submit cloudlet list to the broker
            broker.submitCloudletList(cloudletList);
            
            // Start monitoring for auto-scaling
            broker.startMonitoring(MONITORING_INTERVAL);

            // Start the simulation
            CloudSim.startSimulation();

            // Get received cloudlets
            List<Cloudlet> receivedCloudlets = broker.getCloudletReceivedList();

            // Stop the simulation
            CloudSim.stopSimulation();

            // Print results
            printCloudletList(receivedCloudlets);

            // Print auto-scaling statistics
            Log.printLine("BasicAutoScalingExample finished!");
            Log.printLine("Total VMs created: " + totalVmsCreated);
            Log.printLine("Total VMs destroyed: " + totalVmsDestroyed);
            
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("The simulation has been terminated due to an unexpected error");
        }
    }

    /**
     * Creates the datacenter
     */
    private static Datacenter createDatacenter(String name) {
        // Create list to store hosts
        List<Host> hostList = new ArrayList<>();

        // Host characteristics
        int hostId = 0;
        int ram = 8192; // 8GB RAM
        long storage = 1000000; // 1TB storage
        int bw = 10000; // 10Gbps network bandwidth

        // Create PEs (CPU cores)
        List<Pe> peList = new ArrayList<>();
        int mips = 3000; // PE MIPS rating
        
        // Create 4 PEs (CPU cores)
        for (int i = 0; i < 4; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }

        // Create Host with its id and list of PEs and add them to the list of machines
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

        // Create datacenter characteristics
        String arch = "x86"; // system architecture
        String os = "Linux"; // operating system
        String vmm = "Xen";
        double time_zone = 10.0; // time zone this resource located
        double cost = 3.0; // the cost of using processing in this resource
        double costPerMem = 0.05; // the cost of using memory in this resource
        double costPerStorage = 0.001; // the cost of using storage in this resource
        double costPerBw = 0.0; // the cost of using bw in this resource
        
        LinkedList<Storage> storageList = new LinkedList<>(); // no storage devices

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem,
                costPerStorage, costPerBw);

        // Create Datacenter with characteristics
        Datacenter datacenter = null;
        try {
            datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return datacenter;
    }

    /**
     * Creates the custom auto-scaling broker
     */
    private static MyAutoScalingBroker createBroker() {
        MyAutoScalingBroker broker = null;
        try {
            broker = new MyAutoScalingBroker("MyAutoScalingBroker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return broker;
    }

    /**
     * Prints the Cloudlet objects
     */
    private static void printCloudletList(List<Cloudlet> list) {
        int size = list.size();
        Cloudlet cloudlet;

        String indent = "    ";
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + indent + "STATUS" + indent
                + "Data center ID" + indent + "VM ID" + indent + "Time" + indent
                + "Start Time" + indent + "Finish Time");

        DecimalFormat dft = new DecimalFormat("###.##");
        for (int i = 0; i < size; i++) {
            cloudlet = list.get(i);
            Log.print(indent + cloudlet.getCloudletId() + indent + indent);

            if (cloudlet.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                Log.print("SUCCESS");

                Log.printLine(indent + indent + cloudlet.getResourceId()
                        + indent + indent + indent + cloudlet.getVmId()
                        + indent + indent
                        + dft.format(cloudlet.getActualCPUTime()) + indent
                        + indent + dft.format(cloudlet.getExecStartTime())
                        + indent + indent
                        + dft.format(cloudlet.getFinishTime()));
            }
        }
    }

    /**
     * A custom auto-scaling broker class
     */
    private static class MyAutoScalingBroker extends DatacenterBroker {
        /** The list of VMs created */
        private List<Vm> vmCreatedList;
        
        /** The next VM ID to be created */
        private int nextVmId = 1;
        
        /** Map to track VM utilization */
        private Map<Integer, Double> vmUtilizationMap;

        /**
         * Creates a new broker
         */
        public MyAutoScalingBroker(String name) throws Exception {
            super(name);
            this.vmCreatedList = new ArrayList<>();
            this.vmUtilizationMap = new HashMap<>();
        }
        
        /**
         * Starts the VM monitoring process
         */
        public void startMonitoring(double interval) {
            schedule(getId(), interval, MONITOR_VM_TAG);
        }
        
        /**
         * Process events dispatched to the broker
         */
        @Override
        public void processEvent(SimEvent ev) {
            if (ev.getTag() == MONITOR_VM_TAG) {
                processVmMonitoring();
                schedule(getId(), MONITORING_INTERVAL, MONITOR_VM_TAG);
            } else {
                super.processEvent(ev);
            }
        }
        
        /**
         * Process VM creation acknowledgment
         */
        @Override
        protected void processVmCreateAck(SimEvent ev) {
            int[] data = (int[]) ev.getData();
            int datacenterId = data[0];
            int vmId = data[1];
            int result = data[2];

            if (result == 1) { // Success
                // Get the VM from the original submission list
                Vm vm = null;
                for (Object vmObj : getGuestList()) {
                    if (vmObj instanceof Vm) {
                        Vm tempVm = (Vm) vmObj;
                        if (tempVm.getId() == vmId) {
                            vm = tempVm;
                            break;
                        }
                    }
                }
                
                if (vm != null) {
                    vmCreatedList.add(vm);
                    vmUtilizationMap.put(vmId, 0.0); // Initialize utilization
                    Log.printLine(CloudSim.clock() + ": " + getName() +
                            ": VM #" + vmId + " has been created in Datacenter #" + datacenterId);
                }
            }
            
            // Call the superclass method to complete VM creation process
            super.processVmCreateAck(ev);
        }
        
        /**
         * Process VM monitoring
         */
        protected void processVmMonitoring() {
            if (vmCreatedList.isEmpty()) {
                return;
            }
            
            Log.printLine(CloudSim.clock() + ": " + getName() + ": Monitoring VMs...");
            
            // Calculate utilization for each VM
            double totalUtilization = 0.0;
            int activeVms = 0;
            
            for (Vm vm : vmCreatedList) {
                double vmUtilization = calculateVmUtilization(vm);
                vmUtilizationMap.put(vm.getId(), vmUtilization);
                totalUtilization += vmUtilization;
                activeVms++;
                
                Log.printLine(CloudSim.clock() + ": " + getName() + ": VM #" + vm.getId() +
                        " utilization: " + String.format("%.2f", vmUtilization * 100) + "%");
            }
            
            // Calculate average utilization
            if (activeVms > 0) {
                double avgUtilization = totalUtilization / activeVms;
                Log.printLine(CloudSim.clock() + ": " + getName() + 
                        ": Average utilization: " + String.format("%.2f", avgUtilization * 100) + "%");
                
                // Check for scaling decisions
                if (avgUtilization > CPU_UPPER_THRESHOLD) {
                    // Scale up - need more VMs
                    scaleUp();
                } else if (avgUtilization < CPU_LOWER_THRESHOLD && activeVms > 1) {
                    // Scale down - too many VMs
                    scaleDown();
                }
            }
        }
        
        /**
         * Calculate VM CPU utilization based on its cloudlets
         */
        private double calculateVmUtilization(Vm vm) {
            double totalUtilization = 0.0;
            int count = 0;
            
            // Check each cloudlet
            for (Cloudlet cloudlet : getCloudletSubmittedList()) {
                if (cloudlet.getVmId() == vm.getId() && 
                    cloudlet.getStatus() == Cloudlet.CloudletStatus.INEXEC) {
                    totalUtilization += cloudlet.getUtilizationOfCpu(CloudSim.clock());
                    count++;
                }
            }
            
            return count > 0 ? totalUtilization / count : 0.0;
        }
        
        /**
         * Scale up by creating a new VM
         */
        private void scaleUp() {
            if (vmCreatedList.isEmpty()) {
                return;
            }
            
            // Use first VM as reference for new VM
            Vm refVm = vmCreatedList.get(0);
            
            // Create new VM with same characteristics
            Vm newVm = new Vm(
                nextVmId, 
                getId(), 
                refVm.getMips(), 
                refVm.getNumberOfPes(), 
                refVm.getRam(), 
                refVm.getBw(), 
                refVm.getSize(), 
                refVm.getVmm(), 
                new CloudletSchedulerTimeShared()
            );
            
            Log.printLine(CloudSim.clock() + ": " + getName() + 
                    ": SCALING UP - Creating new VM #" + nextVmId);
            
            // Add VM to lists
            List<Vm> newVmList = new ArrayList<>();
            newVmList.add(newVm);
            
            // Submit new VM for creation
            submitGuestList(newVmList);
            
            // Get datacenter ID where existing VMs are running
            if (!getVmsToDatacentersMap().isEmpty()) {
                int datacenterId = getVmsToDatacentersMap().get(refVm.getId());
                createVmsInDatacenter(datacenterId);
            }
            
            // Update counters
            nextVmId++;
            totalVmsCreated++;
        }
        
        /**
         * Scale down by destroying a VM
         */
        private void scaleDown() {
            if (vmCreatedList.size() <= 1) {
                return; // Keep at least one VM
            }
            
            // Find VM with lowest utilization
            Vm vmToRemove = null;
            double lowestUtilization = Double.MAX_VALUE;
            
            for (Vm vm : vmCreatedList) {
                Double utilization = vmUtilizationMap.get(vm.getId());
                
                // Check if VM has lower utilization but is not the first VM
                if (vm.getId() != 0 && utilization != null && utilization < lowestUtilization) {
                    lowestUtilization = utilization;
                    vmToRemove = vm;
                }
            }
            
            if (vmToRemove == null) {
                return;
            }
            
            Log.printLine(CloudSim.clock() + ": " + getName() + 
                    ": SCALING DOWN - Destroying VM #" + vmToRemove.getId());
            
            // Migrate any running cloudlets to other VMs
            for (Cloudlet cloudlet : getCloudletSubmittedList()) {
                if (cloudlet.getVmId() == vmToRemove.getId() && 
                    cloudlet.getStatus() == Cloudlet.CloudletStatus.INEXEC) {
                    
                    // Find another VM to host this cloudlet
                    for (Vm vm : vmCreatedList) {
                        if (vm.getId() != vmToRemove.getId()) {
                            cloudlet.setVmId(vm.getId());
                            Log.printLine(CloudSim.clock() + ": " + getName() + 
                                    ": Migrating Cloudlet #" + cloudlet.getCloudletId() + 
                                    " from VM #" + vmToRemove.getId() + " to VM #" + vm.getId());
                            break;
                        }
                    }
                }
            }
            
            // Remove VM from lists
            int vmId = vmToRemove.getId();
            int datacenterId = getVmsToDatacentersMap().get(vmId);
            vmCreatedList.remove(vmToRemove);
            vmUtilizationMap.remove(vmId);
            
            // Destroy VM in datacenter
            sendNow(datacenterId, CloudActionTags.VM_DESTROY, vmToRemove);
            totalVmsDestroyed++;
        }
    }
} 