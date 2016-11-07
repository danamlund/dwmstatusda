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
        new MBeanData.Builder().id("")
        .name("")
        .cpuTime(0L)
        .gcTime(0L)
        .heapMemory(NONE_MEMORY_USAGE)
        .nonHeapMemory(NONE_MEMORY_USAGE)
        .openFileDescriptorCount(0L)
        .maxFileDescriptorCount(0L)
        .threadCount(0)
        .nioBufferPoolDirectMemoryUsed(0L)
        .nioBufferPoolMappedMemoryUsed(0L)
        .loadedClassCount(0)
        .processCpuLoad(0.0)
        .finish();
    private static final double WARN_GC_PERCENTAGE = 0.2;
    private static final double WARN_MEM_PERCENTAGE = 1.1;
    private static final int WARN_FILE_DESCRIPTORS = 500;
    private static final int WARN_LIVE_THREADS = 500;
    private static final int WARN_BUFFERPOOL = 1_000_000_000; // ~1G
    private static final int WARN_LOADED_CLASSES = 1_000_000;

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
                System.out.println("  -c:      print single-character summary for each jvm");
                System.out.println("Columns descriptions:");
                System.out.println("  C        Single-character description");
                System.out.println("  GC/CPU   The fraction the jvm have used garbage collecting");
                System.out.println("  GC       Time used garbage collecting in ms");
                System.out.println("  CPU      Cpu time used in ms");
                System.out.println("  LOAD     Cpu load");
                System.out.println("  MEM      Heap and non-heap memory used");
                System.out.println("  MEM+     Memory allocated to the jvm by the os");
                System.out.println("  MAX      Max allowed memory to allocate");
                System.out.println("  FILES    Number of open file descriptors");
                System.out.println("  THREADS  Number of live threads");
                System.out.println("  FSMEM    Size of direct+mapped buffer pools");
                System.out.println("  CLASSES  Number of loaded classes");
                System.out.println("  NAME     The arguments used to start the jvm");
                System.out.println("Single-character descriptions (order of priority):");
                System.out.println("  G        GC usage > " + WARN_GC_PERCENTAGE);
                System.out.println("  M        MEM+ / MAX > " + WARN_MEM_PERCENTAGE);
                System.out.println("  F        File descriptors > " + WARN_FILE_DESCRIPTORS);
                System.out.println("  T        Live threads > " + WARN_LIVE_THREADS);
                System.out.println("  B        Buffer pool > " + humanBytes(WARN_BUFFERPOOL));
                System.out.println("  C        Loaded classes > " + WARN_LOADED_CLASSES);
                System.out.println("  0-9      CPU usage (0=0%, 5=50%, 9=100%)");
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
        if (setArgs.contains('c')) {
            printChars(pid, setArgs.contains('1'));
        } else {
            printAll(pid, setArgs.contains('1'));
        }
    }

    private static List<MBeanData> getBeans(String pid) {
        String runningJvmId = getRunningJvmId();
        List<MBeanData> beans = new ArrayList<>();
        for (VirtualMachineDescriptor desc : VirtualMachine.list()) {
            if (desc.id().equals(runningJvmId)) {
                // do not include the running jvm
                continue;
            }
            if (pid == null || pid.equals(desc.id())) {
                try {
                    MBeanData beanData = getMBeanData(desc);
                    if (beanData != null) {
                        beans.add(beanData);
                    } else {
                        beans.add(ZERO_MBEAN_DATA);
                    }
                } catch (Exception e) {
                    beans.add(ZERO_MBEAN_DATA);
                }
            }
        }
        return beans;
    }

    private static void printChars(String pid, boolean oneSecond) {
        Map<String, MBeanData> oldBeans = new HashMap<>();
        if (oneSecond) {
            for (MBeanData bean : getBeans(pid)) {
                oldBeans.put(bean.id, bean);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // empty
            }
        }

        List<Character> chars = new ArrayList<>();

        for (MBeanData beanData : getBeans(pid)) {
            chars.add(getChar(beanData, oldBeans.get(beanData.id)));
        }

        for (Character c : chars) {
            System.out.print(c);
        }
        System.out.println();
    }

    private static char getChar(MBeanData bean, MBeanData oldBean) {
        if (oldBean != null) {
            if (bean.getGcFraction(oldBean) > WARN_GC_PERCENTAGE) {
                return 'G';
            }
        }

        if ((double) (bean.heapMemory.getUsed() + bean.nonHeapMemory.getUsed()) 
            / bean.heapMemory.getMax() > WARN_MEM_PERCENTAGE) {
            return 'M';
        }
        
        if (bean.openFileDescriptorCount > WARN_FILE_DESCRIPTORS) {
            return 'F';
        }
        
        if (bean.threadCount > WARN_LIVE_THREADS) {
            return 'T';
        }
        
        if (bean.nioBufferPoolDirectMemoryUsed + bean.nioBufferPoolMappedMemoryUsed 
            > WARN_BUFFERPOOL) {
            return 'B';
        }
        
        if (bean.loadedClassCount > WARN_LOADED_CLASSES) {
            return 'C';
        }
        
        double load = bean.processCpuLoad;
        if (oldBean != null) {
            load = Math.max(load, oldBean.processCpuLoad);
        }
        
        int loadDigit = (int) Math.round(load * 10.0);
        return String.valueOf(Math.min(9, Math.max(0, loadDigit))).charAt(0);
    }

    private static void printAll(String pid, boolean oneSecond) {
        Map<String, MBeanData> oldBeans = new HashMap<>();
        if (oneSecond) {
            for (MBeanData bean : getBeans(pid)) {
                oldBeans.put(bean.id, bean);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // empty
            }
        }

        List<List<String>> rows = new ArrayList<>();

        for (MBeanData beanData : getBeans(pid)) {
            List<String> row = new ArrayList<>();

            row.add(""+getChar(beanData, oldBeans.get(beanData.id)));

            row.add(beanData.id);
            if (oneSecond) {
                // value changes in last second
                if (oldBeans.containsKey(beanData.id)) {
                    MBeanData old = oldBeans.get(beanData.id);
                    row.add(format(beanData.getGcFraction(old)));
                    row.add("" + (beanData.gcTime - old.gcTime));
                    row.add("" + (beanData.getCpuTimeMs() - old.getCpuTimeMs()));
                } else {
                    row.add(format(0.00));
                    row.add("0");
                    row.add("0");
                }
            } else {
                row.add(format(beanData.getGcFraction(ZERO_MBEAN_DATA)));
                row.add("" + beanData.gcTime);
                row.add("" + beanData.getCpuTimeMs());
            }
            row.add(format(beanData.processCpuLoad));
            row.add(beanData.getUsedMem());
            row.add(beanData.getUsedOsMem());
            row.add(beanData.getMaxMem());

            row.add("" + beanData.openFileDescriptorCount);
            row.add("" + beanData.threadCount);
            row.add(beanData.getBufferPollMem());
            row.add("" + beanData.loadedClassCount);

            row.add(beanData.name);

            rows.add(row);
        }

        printTable(rows);
    }
    
    private static void printTable(List<List<String>> rows) {
        List<String> columnNames = 
            Arrays.asList("C", "PID", "GC/CPU", "GC", "CPU", "LOAD", "MEM", "MEM+", "MAX", 
                          "FILES", "THREADS", "FSMEM", "CLASSES", "NAME");

        List<Integer> maxColumnLengths = new ArrayList<>();
        // - 1: last column is not padded
        for (int i = 0; i < columnNames.size() - 1; i++) {
            int maxLength = columnNames.get(i).length();
            for (List<String> row : rows) {
                int length = row.get(i).length();
                if (length > maxLength) {
                    maxLength = length;
                }
            }
            maxColumnLengths.add(maxLength);
        }

        StringBuilder fmt = new StringBuilder();
        for (Integer maxLength : maxColumnLengths) {
            fmt.append("%" + maxLength + "s   ");
        }
        fmt.append("%s%n"); // last column and newline
        
        System.out.format(fmt.toString(), columnNames.toArray());
        for (List<String> row : rows) {
            System.out.format(fmt.toString(), row.toArray());
        }
    }

    private static String format(double d) {
        return String.format("%.2f", d);
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
        MBeanData.Builder builder = new MBeanData.Builder();
        builder.id(vmDesc.id());
        builder.name(vmDesc.displayName());
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

                    builder
                        .cpuTime(getCpuTime(mbeanConn))
                        .gcTime(getGcTime(mbeanConn))
                        .heapMemory(getHeapMemoryUsage(mbeanConn))
                        .nonHeapMemory(getNonHeapMemoryUsage(mbeanConn))
                        .openFileDescriptorCount(getOpenFileDescriptorCount(mbeanConn))
                        .maxFileDescriptorCount(getMaxFileDescriptorCount(mbeanConn))
                        .threadCount(getThreadCount(mbeanConn))
                        .nioBufferPoolDirectMemoryUsed(getNioBufferPoolDirectMemoryUsed(mbeanConn))
                        .nioBufferPoolMappedMemoryUsed(getNioBufferPoolMappedMemoryUsed(mbeanConn))
                        .loadedClassCount(getLoadedClassCount(mbeanConn))
                        .processCpuLoad(getProcessCpuLoad(mbeanConn));
                }
        } catch (Exception e) {
            return null;
        }

        return builder.finish();
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

    private static long getOpenFileDescriptorCount(MBeanServerConnection conn) {
        try {
            ObjectName osName = new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
            return (Long) conn.getAttribute(osName, "OpenFileDescriptorCount");
        } catch (Exception e) {
            return -1L;
        }
    }

    private static long getMaxFileDescriptorCount(MBeanServerConnection conn) {
        try {
            ObjectName osName = new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
            return (Long) conn.getAttribute(osName, "MaxFileDescriptorCount");
        } catch (Exception e) {
            return -1L;
        }
    }

    private static int getThreadCount(MBeanServerConnection conn) {
        try {
            ObjectName osName = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
            return (Integer) conn.getAttribute(osName, "ThreadCount");
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    private static long getNioBufferPoolDirectMemoryUsed(MBeanServerConnection conn) {
        try {
            return (Long) conn.getAttribute(new ObjectName("java.nio:type=BufferPool,name=direct"),
                                     "MemoryUsed");
        } catch (Exception e) {
            return -1L;
        }
    }

    private static long getNioBufferPoolMappedMemoryUsed(MBeanServerConnection conn) {
        try {
            return (Long) conn.getAttribute(new ObjectName("java.nio:type=BufferPool,name=mapped"),
                                     "MemoryUsed");
        } catch (Exception e) {
            return -1L;
        }
    }

    private static int getLoadedClassCount(MBeanServerConnection conn) {
        try {
            ObjectName clName = new ObjectName(ManagementFactory.CLASS_LOADING_MXBEAN_NAME);
            return (Integer) conn.getAttribute(clName, "LoadedClassCount");
        } catch (Exception e) {
            return -1;
        }
    }

    private static double getProcessCpuLoad(MBeanServerConnection conn) {
        try {
            ObjectName osName = new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
            return (Double) conn.getAttribute(osName, "ProcessCpuLoad");
        } catch (Exception e) {
            return -1.0;
        }
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
        public final long openFileDescriptorCount;
        public final long maxFileDescriptorCount;
        public final int threadCount;
        public final long nioBufferPoolDirectMemoryUsed;
        public final long nioBufferPoolMappedMemoryUsed;
        public final int loadedClassCount;
        public final double processCpuLoad;

        private MBeanData(Builder builder) {
            this.id = Objects.requireNonNull(builder.id);
            this.name = Objects.requireNonNull(builder.name);
            this.cpuTime = builder.cpuTime.longValue();
            this.gcTime = builder.gcTime.longValue();
            this.heapMemory = Objects.requireNonNull(builder.heapMemory);
            this.nonHeapMemory = Objects.requireNonNull(builder.nonHeapMemory);
            this.openFileDescriptorCount = builder.openFileDescriptorCount.longValue();
            this.maxFileDescriptorCount = builder.maxFileDescriptorCount.longValue();
            this.threadCount = builder.threadCount.intValue();
            this.nioBufferPoolDirectMemoryUsed = 
                builder.nioBufferPoolDirectMemoryUsed.longValue();
            this.nioBufferPoolMappedMemoryUsed = 
                builder.nioBufferPoolMappedMemoryUsed.longValue();
            this.loadedClassCount = builder.loadedClassCount.intValue();
            this.processCpuLoad = builder.processCpuLoad.doubleValue();
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
        public long getCpuTimeMs() {
            return cpuTime / 1_000_000;
        }

        public String getBufferPollMem() {
            return humanBytes(nioBufferPoolDirectMemoryUsed + nioBufferPoolMappedMemoryUsed);
        }

        @Override
        public String toString() {
            return "MBeanData["+id+"("+name+"), cpu="+cpuTime+", gc="+gcTime+"]";
        }

        private static class Builder {
            private String id;
            private String name;
            private Long cpuTime;
            private Long gcTime;
            private MemoryUsage heapMemory;
            private MemoryUsage nonHeapMemory;
            private Long openFileDescriptorCount;
            private Long maxFileDescriptorCount;
            private Integer threadCount;
            private Long nioBufferPoolDirectMemoryUsed;
            private Long nioBufferPoolMappedMemoryUsed;
            private Integer loadedClassCount;
            private Double processCpuLoad;

            public Builder id(String id) {
                this.id = id;
                return this;
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }
            public Builder cpuTime(long cpuTime) {
                this.cpuTime = cpuTime;
                return this;
            }
            public Builder gcTime(long gcTime) {
                this.gcTime = gcTime;
                return this;
            }
            public Builder heapMemory(MemoryUsage heapMemory) {
                this.heapMemory = heapMemory;
                return this;
            }
            public Builder nonHeapMemory(MemoryUsage nonHeapMemory) {
                this.nonHeapMemory = nonHeapMemory;
                return this;
            }
            public Builder openFileDescriptorCount(long openFileDescriptorCount) {
                this.openFileDescriptorCount = openFileDescriptorCount;
                return this;
            }
            public Builder maxFileDescriptorCount(long maxFileDescriptorCount) {
                this.maxFileDescriptorCount = maxFileDescriptorCount;
                return this;
            }
            public Builder threadCount(int threadCount) {
                this.threadCount = threadCount;
                return this;
            }
            public Builder nioBufferPoolDirectMemoryUsed(long nioBufferPoolDirectMemoryUsed) {
                this.nioBufferPoolDirectMemoryUsed = nioBufferPoolDirectMemoryUsed;
                return this;
            }
            public Builder nioBufferPoolMappedMemoryUsed(long nioBufferPoolMappedMemoryUsed) {
                this.nioBufferPoolMappedMemoryUsed = nioBufferPoolMappedMemoryUsed;
                return this;
            }
            public Builder loadedClassCount(int loadedClassCount) {
                this.loadedClassCount = loadedClassCount;
                return this;
            }
            public Builder processCpuLoad(double processCpuLoad) {
                this.processCpuLoad = processCpuLoad;
                return this;
            }
            public MBeanData finish() {
                return new MBeanData(this);
            }
        }
    }
}
