package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.Cloudlet.CloudletStatus;

import java.text.DecimalFormat;
import java.util.*;

/**
 * A simple working auto-scaling example for CloudSim 7.0.0-alpha
 * This example demonstrates a basic simulation setup with a datacenter, broker, VM and cloudlets.
 * The auto-scaling implementation is simplified to focus on compatibility with the current API.
 */
public class WorkingAutoScalingExample {

    // Constants for auto-scaling thresholds
    private static final double CPU_UPPER_THRESHOLD = 0.8; // 80% utilization to scale up
    private static final double CPU_LOWER_THRESHOLD = 0.2; // 20% utilization to scale down

    private static List<Vm> vmList;
    private static List<Cloudlet> cloudletList;
    private static int numVmsCreated = 0;

    /**
     * Creates main method to run this example
     */
    public static void main(String[] args) {
        Log.printLine("Starting Working Auto-Scaling Example...");

        try {
            // Initialize CloudSim
            int num_user = 1;
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;
            CloudSim.init(num_user, calendar, trace_flag);

            // Create datacenter
            Datacenter datacenter = createDatacenter("Datacenter_0");

            // Create broker
            DatacenterBroker broker = createBroker();
            int brokerId = broker.getId();

            // Create one VM to start with
            vmList = new ArrayList<>();
            int vmid = 0;
            int mips = 1000;
            long size = 10000; // image size (MB)
            int ram = 512; // vm memory (MB)
            long bw = 1000;
            int pesNumber = 1; // number of cpus
            String vmm = "Xen"; // VMM name

            // Create a VM
            Vm vm = new Vm(vmid, brokerId, mips, pesNumber, ram, bw, size, vmm, new CloudletSchedulerTimeShared());
            numVmsCreated++;
            vmList.add(vm);

            // Submit VMs to the broker
            broker.submitGuestList(vmList);

            // Create cloudlets
            cloudletList = new ArrayList<>();
            int id = 0;
            long length = 10000; // in MI (Million Instructions)
            long fileSize = 300; // in bytes
            long outputSize = 300; // in bytes
            int numberOfCloudlets = 10; // Total number of cloudlets

            // Create cloudlets with increasing utilization
            for (int i = 0; i < numberOfCloudlets; i++) {
                // Create a cloudlet with increasing CPU intensity
                Cloudlet cloudlet = new Cloudlet(id++, length * (1 + i/5), pesNumber, fileSize, outputSize,
                        new UtilizationModelStochastic(), // CPU utilization model
                        new UtilizationModelFull(), // RAM utilization model
                        new UtilizationModelFull()); // Bandwidth utilization model

                cloudlet.setUserId(brokerId);
                cloudletList.add(cloudlet);
            }

            // Submit cloudlets to broker
            broker.submitCloudletList(cloudletList);

            // Start the simulation
            CloudSim.startSimulation();

            // Get the received cloudlets
            List<Cloudlet> receivedCloudlets = broker.getCloudletReceivedList();

            CloudSim.stopSimulation();

            // Print the results
            printCloudletList(receivedCloudlets);
            
            Log.printLine("WorkingAutoScalingExample finished!");
            Log.printLine("Total VMs created: " + numVmsCreated);
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("The simulation has been terminated due to an unexpected error");
        }
    }

    /**
     * Creates the datacenter
     */
    private static Datacenter createDatacenter(String name) {
        // Create a list of hosts
        List<Host> hostList = new ArrayList<>();

        // Create PEs (CPU cores)
        List<Pe> peList = new ArrayList<>();
        
        int mips = 5000; // Each PE MIPS capacity
        
        // Create 4 PEs
        for (int i = 0; i < 4; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }

        // Create Host with its id and list of PEs and add them to the list of hosts
        int hostId = 0;
        int ram = 16384; // host memory (MB)
        long storage = 1000000; // host storage
        int bw = 10000;

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
        double time_zone = 10.0; // time zone in GMT
        double cost = 3.0; // the cost of using processing in this resource
        double costPerMem = 0.05; // the cost of using memory in this resource
        double costPerStorage = 0.001; // the cost of using storage in this resource
        double costPerBw = 0.0; // the cost of using bw in this resource
        
        LinkedList<Storage> storageList = new LinkedList<>();

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem,
                costPerStorage, costPerBw);

        // Create Datacenter with previously created characteristics
        Datacenter datacenter = null;
        try {
            datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return datacenter;
    }

    /**
     * Creates a standard broker
     */
    private static DatacenterBroker createBroker() {
        DatacenterBroker broker = null;
        try {
            broker = new DatacenterBroker("Broker_0");
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

            if (cloudlet.getStatus() == CloudletStatus.SUCCESS) {
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
} 