import com.sun.tools.attach.*;
import java.lang.management.*;
import javax.management.*;
import javax.management.remote.*;
import javax.management.openmbean.*;

import java.util.*;
import java.util.function.*;
import java.io.*;

public class JvmGcStats {
    private static final MemoryUsage NONE_MEMORY_USAGE = new MemoryUsage(0L, 0L, 0L, 0L);
    private static final MBeanData ZERO_MBEAN_DATA = 
        new MBeanData("", "", 0, 0, NONE_MEMORY_USAGE, NONE_MEMORY_USAGE);

    public static void main(String[] args) {
        
        if (args.length == 0) {
            printAll(null, false);
            System.exit(0);
        }

        Set<Character> setArgs = new HashSet<>();
        String pid = null;
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                System.out.println("Usage: JvmGcStats [-f|-1|-c|-g] [PID]");
                System.out.println("  no args: print all data");
                System.out.println("  -1:      print gc data over 1 second (rather than lifetime)");
                System.out.println("Columns descriptions:");
                System.out.println("  GC/CPU   The fraction the jvm have used garbage collecting");
                System.out.println("  GC       Time used garbage collecting in ms");
                System.out.println("  CPU      Cpu time used in ms");
                System.out.println("  MEM      Heap and non-heap memory used");
                System.out.println("  MEM+     Memory allocated to the jvm by the os");
                System.out.println("  MAX      Max allowed memory to allocate");
                System.out.println("  NAME     The arguments used to start the jvm");
                System.out.println();
                System.exit(1);
            }
            else if (arg.startsWith("-")) {
                for (int i = 1; i < arg.length(); i++) {
                    setArgs.add(arg.charAt(i));
                }
            }
            else {
                pid = arg;
            }
        }
        printAll(pid, setArgs.contains('1'));
    }

    private static void printAll(String pid, boolean oneSecond) {
        Map<String, MBeanData> oldBeans = new HashMap<>();
        if (oneSecond) {
            for (VirtualMachineDescriptor desc : VirtualMachine.list()) {
                if (pid == null || pid.equals(desc.id())) {
                    MBeanData beanData = getMBeanData(desc);
                    if (beanData != null) {
                        oldBeans.put(beanData.id, beanData);
                    }
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // empty
            }
        }
        String fmt = "%8s  %6s  %7s  %7s  %4s  %4s  %4s  %s\n";
        System.out.format(fmt, "PID", "GC/CPU", "GC", "CPU", "MEM", "MEM+", "MAX", "NAME");
        List<VirtualMachineDescriptor> vmDescs = new ArrayList<>(VirtualMachine.list());
        vmDescs.sort(Comparator.comparingInt(vmDesc -> Integer.parseInt(vmDesc.id())));
        for (VirtualMachineDescriptor desc : vmDescs) {
            if (desc.id().equals(getRunningJvmId())) {
                // do not include the running jvm
                continue;
            }
            if (pid == null || pid.equals(desc.id())) {
                MBeanData beanData = getMBeanData(desc);
                if (beanData != null) {
                    final double gcFraction;
                    if (oldBeans.containsKey(beanData.id)) {
                        gcFraction = beanData.getGcFraction(oldBeans.get(beanData.id));
                    } else {
                        gcFraction = beanData.getGcFraction(ZERO_MBEAN_DATA);
                    }
                    
                    System.out.format(fmt,  
                                      beanData.id, 
                                      String.format("%.2f", gcFraction), 
                                      beanData.gcTime, 
                                      beanData.cpuTime / 1_000_000, 
                                      beanData.getUsedMem(),
                                      beanData.getUsedOsMem(),
                                      beanData.getMaxMem(),
                                      beanData.name);
                }
            }
        }
    }

    private static String getRunningJvmId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        if (name.contains("@")) {
            return name.substring(0, name.indexOf("@"));
        } else {
            return null;
        }
    }

    private static MBeanData getMBeanData(VirtualMachineDescriptor vmDesc) {
        String id = vmDesc.id();
        String name = vmDesc.displayName(); // .split(" ")[0];
        long cpuTime = -1;
        long gcTime = -1;
        MemoryUsage heapMemoryUsage;
        MemoryUsage nonHeapMemoryUsage;
        try {
            VirtualMachine vm = VirtualMachine.attach(vmDesc);
            Properties props = vm.getAgentProperties();
            String connectorAddress = vm.getAgentProperties()
                .getProperty("com.sun.management.jmxremote.localConnectorAddress");
            if (connectorAddress == null) {
                // start up JMX on the virtual machine
                String agent = vm.getSystemProperties().getProperty("java.home") +
                    File.separator + "lib" + File.separator + 
                    "management-agent.jar";
                vm.loadAgent(agent);
                connectorAddress = vm.getAgentProperties()
                    .getProperty("com.sun.management.jmxremote.localConnectorAddress");
            }
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            try (JMXConnector connector = JMXConnectorFactory.connect(url);) {
                    MBeanServerConnection mbeanConn = connector.getMBeanServerConnection();
                    cpuTime = getCpuTime(mbeanConn);
                    gcTime = getGcTime(mbeanConn);
                    heapMemoryUsage = getHeapMemoryUsage(mbeanConn);
                    nonHeapMemoryUsage = getNonHeapMemoryUsage(mbeanConn);
                }
        } catch (Exception e) {
            return null;
        }

        return new MBeanData(id, name, cpuTime, gcTime, heapMemoryUsage, nonHeapMemoryUsage);
    }

    private static Long getCpuTime(MBeanServerConnection conn) {
        try {
            ObjectName osName = new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);

            MBeanInfo info = conn.getMBeanInfo(osName);
            MBeanAttributeInfo[] attrs = info.getAttributes();
            
            long processCPUTimeMultiplier = 1;
            for (MBeanAttributeInfo attr : attrs) {
                String name = attr.getName();
                if ("ProcessingCapacity".equals(name)) {
                    Number mul = (Number) conn.getAttribute(osName, name);
                    processCPUTimeMultiplier = mul.longValue();
                }
            }
            
            Long cputime = (Long) conn.getAttribute(osName, "ProcessCpuTime");
            
            return cputime * processCPUTimeMultiplier;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static Long getGcTime(MBeanServerConnection conn) {
        try {
            long gcTimeTotal = 0;
            ObjectName gcNames = 
                new ObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*");
            for (ObjectName name : conn.queryNames(gcNames, null)) {
                long gcTime = (Long) conn.getAttribute(name, "CollectionTime");
                gcTimeTotal += gcTime;
            }
            return gcTimeTotal;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static MemoryUsage getHeapMemoryUsage(MBeanServerConnection conn) {
        try {
            ObjectName memName = 
                new ObjectName(ManagementFactory.MEMORY_MXBEAN_NAME);
                return MemoryUsage.from((CompositeData) conn.getAttribute(memName, "HeapMemoryUsage"));
        } catch (Exception e) {
            return NONE_MEMORY_USAGE;
        }
    }

    private static MemoryUsage getNonHeapMemoryUsage(MBeanServerConnection conn) {
        try {
            ObjectName memName = 
                new ObjectName(ManagementFactory.MEMORY_MXBEAN_NAME);
            return MemoryUsage.from((CompositeData) conn.getAttribute(memName, "NonHeapMemoryUsage"));
        } catch (Exception e) {
            return NONE_MEMORY_USAGE;
        }
    }

    private static void printMem(MemoryUsage memoryUsage) {
        System.out.println("##      init = " + humanBytes(memoryUsage.getInit()));
        System.out.println("##      used = " + humanBytes(memoryUsage.getUsed()));
        System.out.println("##       max = " + humanBytes(memoryUsage.getMax()));
        System.out.println("## committed = " + humanBytes(memoryUsage.getCommitted()));
    }

    // http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
    public static String humanBytes(long bytes) {
        int unit = 1024;
        if (bytes < unit) return "" + bytes;
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = "" + "KMGTPE".charAt(exp-1);
        return String.format("%.0f%s", bytes / Math.pow(unit, exp), pre);
    }
    
    private static class MBeanData {
        public final String id;
        public final String name;
        // in nano seconds
        public final long cpuTime;
        // in milli seconds
        public final long gcTime;
        public final MemoryUsage heapMemory;
        public final MemoryUsage nonHeapMemory;

        public MBeanData(String id, String name, long cpuTime, long gcTime, 
                         MemoryUsage heapMemory, MemoryUsage nonHeapMemory) {
            this.id = id;
            this.name = name;
            this.cpuTime = cpuTime;
            this.gcTime = gcTime;
            this.heapMemory = heapMemory;
            this.nonHeapMemory = nonHeapMemory;
        }

        public double getGcFraction(MBeanData olderData) {
            long cpuTimeDiff = cpuTime - olderData.cpuTime;
            long gcTimeDiff = gcTime - olderData.gcTime;
            gcTimeDiff = gcTimeDiff * 1_000_000; // milli seconds to nano seconds
            return cpuTimeDiff == 0 ? 0.0 : (double) gcTimeDiff / cpuTimeDiff;
        }
        public String getUsedMem() {
            long used = heapMemory.getUsed() + nonHeapMemory.getUsed();
            return humanBytes(used);
        }
        public String getUsedOsMem() {
            long usedOs = heapMemory.getCommitted() + nonHeapMemory.getCommitted();
            return humanBytes(usedOs);
        }
        public String getMaxMem() {
            return humanBytes(heapMemory.getMax());
        }


        @Override
        public String toString() {
            return "MBeanData["+id+"("+name+"), cpu="+cpuTime+", gc="+gcTime+"]";
        }
    }
}
