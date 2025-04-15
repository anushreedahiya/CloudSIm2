package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * A simple example demonstrating basic auto-scaling in CloudSim
 */
public class SimpleAutoscaling {

    // Lists to store VMs and cloudlets
    private static List<Vm> vmList;
    private static List<Cloudlet> cloudletList;
    private static int totalVmsCreated = 1; // Start with 1 VM

    /**
     * The main method to run the example
     */
    public static void main(String[] args) {
        Log.printLine("Starting SimpleAutoscaling example...");

        try {
            // Initialize CloudSim
            int numUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;
            CloudSim.init(numUsers, calendar, traceFlag);

            // Create a datacenter
            Datacenter datacenter = createDatacenter("Datacenter_0");

            // Create a broker
            DatacenterBroker broker = createBroker("Broker_0");
            int brokerId = broker.getId();

            // Create one VM initially
            vmList = new ArrayList<>();
            
            // VM parameters
            int vmid = 0;
            int mips = 1000;
            long size = 10000; // image size (MB)
            int ram = 512; // vm memory (MB)
            long bw = 1000;
            int pesNumber = 1; // number of cpus
            String vmm = "Xen"; // VMM name

            // Create VM
            Vm vm = new Vm(vmid, brokerId, mips, pesNumber, ram, bw, size, vmm, new CloudletSchedulerTimeShared());
            vmList.add(vm);

            // Submit initial VM to the broker
            broker.submitVmList(vmList);

            // Create cloudlets with increasing length to simulate increasing load
            cloudletList = new ArrayList<>();
            
            // Cloudlet parameters
            int id = 0;
            long fileSize = 300;
            long outputSize = 300;
            UtilizationModel utilizationModel = new UtilizationModelFull();
            
            // Create 10 sets of cloudlets with increasing computational requirements
            for (int batch = 0; batch < 10; batch++) {
                // Create 5 cloudlets per batch
                for (int i = 0; i < 5; i++) {
                    // Increase the length for each batch to simulate growing workload
                    long length = 10000 * (batch + 1);
                    
                    Cloudlet cloudlet = new Cloudlet(id++, length, pesNumber, fileSize, outputSize,
                            utilizationModel, utilizationModel, utilizationModel);
                    cloudlet.setUserId(brokerId);
                    
                    // Auto-scaling logic based on current cloudlets and VMs
                    if (id > 10 && id % 5 == 0) {
                        // Check if we need more VMs based on number of cloudlets per VM
                        int currentVmCount = vmList.size();
                        int idealVmCount = (int) Math.ceil(id / 5.0); // 5 cloudlets per VM is ideal
                        
                        if (idealVmCount > currentVmCount) {
                            // Scale up: Create a new VM
                            Log.printLine("Auto-scaling UP: Creating new VM #" + totalVmsCreated);
                            
                            // Create a new VM with the same specs
                            Vm newVm = new Vm(totalVmsCreated, brokerId, mips, pesNumber, ram, bw, size, vmm, 
                                    new CloudletSchedulerTimeShared());
                            
                            // Add to the list and submit
                            vmList.add(newVm);
                            
                            // For simple demo, we're just adding to our list
                            // In a real scenario, you would submit to the broker
                            List<Vm> newVmList = new ArrayList<>();
                            newVmList.add(newVm);
                            broker.submitVmList(newVmList);
                            
                            totalVmsCreated++;
                        }
                    }
                    
                    // Add the cloudlet to the list
                    cloudletList.add(cloudlet);
                }
            }

            // Submit all cloudlets to the broker
            broker.submitCloudletList(cloudletList);

            // Start the simulation
            CloudSim.startSimulation();

            // Get the list of completed cloudlets
            List<Cloudlet> completedCloudlets = broker.getCloudletReceivedList();

            // Stop the simulation
            CloudSim.stopSimulation();

            // Print results
            printCloudletList(completedCloudlets);

            Log.printLine("SimpleAutoscaling example finished!");
            Log.printLine("Total VMs created during simulation: " + totalVmsCreated);
            
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("The simulation has been terminated due to an unexpected error");
        }
    }

    /**
     * Creates a datacenter
     */
    private static Datacenter createDatacenter(String name) {
        // Create list to store hosts
        List<Host> hostList = new ArrayList<>();

        // Create list to store PEs (CPU cores)
        List<Pe> peList = new ArrayList<>();

        int mips = 5000; // capacity of each PE
        
        // Create 4 PEs
        for (int i = 0; i < 4; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }

        // Host parameters
        int hostId = 0;
        int ram = 16384; // host memory (MB)
        long storage = 1000000; // host storage
        int bw = 10000;

        // Create the host
        Host host = new Host(hostId, new RamProvisionerSimple(ram), new BwProvisionerSimple(bw), storage, peList,
                new VmSchedulerTimeShared(peList));
        hostList.add(host);

        // Datacenter characteristics
        String arch = "x86"; // system architecture
        String os = "Linux"; // operating system
        String vmm = "Xen";
        double time_zone = 10.0; // time zone this resource is located
        double cost = 3.0; // the cost of using processing in this resource
        double costPerMem = 0.05; // the cost of using memory in this resource
        double costPerStorage = 0.001; // the cost of using storage in this resource
        double costPerBw = 0.0; // the cost of using bw in this resource
        
        LinkedList<Storage> storageList = new LinkedList<>();

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(arch, os, vmm, hostList,
                time_zone, cost, costPerMem, costPerStorage, costPerBw);

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
     * Creates a broker
     */
    private static DatacenterBroker createBroker(String name) {
        DatacenterBroker broker = null;
        try {
            broker = new DatacenterBroker(name);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return broker;
    }

    /**
     * Prints the list of cloudlets
     */
    private static void printCloudletList(List<Cloudlet> list) {
        int size = list.size();
        Cloudlet cloudlet;

        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + "\t" + "STATUS" + "\t" + "Data center ID" + "\t" + "VM ID" + "\t" + "Time" + "\t"
                + "Start Time" + "\t" + "Finish Time");

        DecimalFormat dft = new DecimalFormat("###.##");
        for (int i = 0; i < size; i++) {
            cloudlet = list.get(i);
            Log.print(cloudlet.getCloudletId() + "\t\t");

            if (cloudlet.getStatus() == Cloudlet.SUCCESS) {
                Log.print("SUCCESS" + "\t");
                Log.printLine(cloudlet.getResourceId() + "\t\t" + cloudlet.getVmId() + "\t\t"
                        + dft.format(cloudlet.getActualCPUTime()) + "\t\t" + dft.format(cloudlet.getExecStartTime())
                        + "\t\t" + dft.format(cloudlet.getFinishTime()));
            }
        }
    }
} 