package org.cloudbus.cloudsim.container.vmSelectionPolicies;


import org.cloudbus.cloudsim.container.core.ContainerVm;
import org.cloudbus.cloudsim.container.core.PowerContainerVm;
import org.cloudbus.cloudsim.container.utils.Correlation;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.PowerHostUtilizationHistory;

import java.util.List;

/**
 * Created by sareh on 16/11/15.
 * Modified by Remo Andreoli (Feb 2024)
 */
public class PowerContainerVMSelectionPolicyCor extends PowerContainerVmSelectionPolicy {


    /**
     * The fallback policy.
     */
    private PowerContainerVmSelectionPolicy fallbackPolicy;

    /**
     * Instantiates a new power vm selection policy maximum correlation.
     *
     * @param fallbackPolicy the fallback policy
     */
    public PowerContainerVMSelectionPolicyCor(final PowerContainerVmSelectionPolicy fallbackPolicy) {
        super();
        setFallbackPolicy(fallbackPolicy);
    }

    /*
    * (non-Javadoc)
    *
    * @see org.cloudbus.cloudsim.experiments.power.PowerVmSelectionPolicy#
    * getVmsToMigrate(org.cloudbus .cloudsim.power.PowerHost)
    */
    @Override
    public ContainerVm getVmToMigrate(final PowerHost host) {
        List<PowerContainerVm> migratableVMs = getMigratableVms(host);
        if (migratableVMs.isEmpty()) {
            return null;
        }
        ContainerVm vm = getContainerVM(migratableVMs, host);
        migratableVMs.clear();
        if (vm != null) {
//            Log.printConcatLine("We have to migrate the container with ID", container.getId());
            return vm;
        } else {
            return getFallbackPolicy().getVmToMigrate(host);
        }
    }

    /**
     * Gets the fallback policy.
     *
     * @return the fallback policy
     */
    public PowerContainerVmSelectionPolicy getFallbackPolicy() {
        return fallbackPolicy;
    }


    /**
     * Sets the fallback policy.
     *
     * @param fallbackPolicy the new fallback policy
     */
    public void setFallbackPolicy(final PowerContainerVmSelectionPolicy fallbackPolicy) {
        this.fallbackPolicy = fallbackPolicy;
    }

    public ContainerVm getContainerVM(List<PowerContainerVm> migratableContainerVMs, PowerHost host) {

        double[] corResult = new double[migratableContainerVMs.size()];
        Correlation correlation = new Correlation();
        int i = 0;
        double maxValue = -2;
        int id = -1;
        if (host instanceof PowerHostUtilizationHistory) {

            double[] hostUtilization = ((PowerHostUtilizationHistory) host).getUtilizationHistory();
            for (PowerContainerVm vm : migratableContainerVMs) {
                double[] containerUtilization = vm.getUtilizationHistoryList();

                double cor = correlation.getCor(hostUtilization, containerUtilization);
                if (Double.isNaN(cor)) {
                    cor = -3;
                }
                corResult[i] = cor;
                
                if(corResult[i] > maxValue) {
                	maxValue = corResult[i];
                	id = i;
                }
                
                i++;
            }

        }

        if (id == -1) {
            Log.printlnConcat("Problem with correlation list.");
        }

        return migratableContainerVMs.get(id);

    }


}













