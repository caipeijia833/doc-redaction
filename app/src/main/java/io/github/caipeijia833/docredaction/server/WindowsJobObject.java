/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.WinBase.SECURITY_ATTRIBUTES;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;

/** Windows process-tree lifecycle and committed-memory containment. */
final class WindowsJobObject implements AutoCloseable {
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;
    private static final int JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
    private static final int JOB_OBJECT_LIMIT_PROCESS_MEMORY = 0x00000100;
    private static final int JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int MAX_ACTIVE_PROCESSES = 32;
    private static final int PROCESS_ACCESS = WinNT.PROCESS_SET_QUOTA
            | WinNT.PROCESS_TERMINATE | WinNT.PROCESS_QUERY_INFORMATION;

    private final HANDLE jobHandle;
    private final boolean required;
    private final long committedMemoryLimitBytes;
    private final String status;

    private WindowsJobObject(HANDLE jobHandle, boolean required, long committedMemoryLimitBytes, String status) {
        this.jobHandle = jobHandle;
        this.required = required;
        this.committedMemoryLimitBytes = committedMemoryLimitBytes;
        this.status = status;
    }

    static WindowsJobObject attach(Process process, long committedMemoryLimitBytes, boolean required)
            throws IOException {
        if (!isWindows()) {
            if (required) {
                throw new IOException("Windows Job Object isolation was required on a non-Windows platform");
            }
            return new WindowsJobObject(null, false, 0L, "not applicable on this platform");
        }
        if (committedMemoryLimitBytes <= 0L) {
            throw new IllegalArgumentException("committedMemoryLimitBytes must be positive");
        }

        HANDLE job = Kernel32Job.INSTANCE.CreateJobObjectW(null, null);
        if (invalid(job)) {
            return failed(required, committedMemoryLimitBytes,
                    "CreateJobObjectW failed with Win32 error " + Native.getLastError());
        }
        boolean success = false;
        try {
            JobObjectExtendedLimitInformation limits = new JobObjectExtendedLimitInformation();
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                    | JOB_OBJECT_LIMIT_ACTIVE_PROCESS
                    | JOB_OBJECT_LIMIT_PROCESS_MEMORY
                    | JOB_OBJECT_LIMIT_JOB_MEMORY;
            limits.BasicLimitInformation.ActiveProcessLimit = MAX_ACTIVE_PROCESSES;
            limits.ProcessMemoryLimit = new SIZE_T(committedMemoryLimitBytes);
            limits.JobMemoryLimit = new SIZE_T(committedMemoryLimitBytes);
            limits.write();
            if (!Kernel32Job.INSTANCE.SetInformationJobObject(job,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, limits.getPointer(), limits.size())) {
                return failedAfterClose(job, required, committedMemoryLimitBytes,
                        "SetInformationJobObject failed with Win32 error " + Native.getLastError());
            }

            HANDLE processHandle = Kernel32Job.INSTANCE.OpenProcess(PROCESS_ACCESS, false, (int) process.pid());
            if (invalid(processHandle)) {
                return failedAfterClose(job, required, committedMemoryLimitBytes,
                        "OpenProcess failed with Win32 error " + Native.getLastError());
            }
            try {
                if (!Kernel32Job.INSTANCE.AssignProcessToJobObject(job, processHandle)) {
                    return failedAfterClose(job, required, committedMemoryLimitBytes,
                            "AssignProcessToJobObject failed with Win32 error " + Native.getLastError());
                }
            } finally {
                Kernel32Job.INSTANCE.CloseHandle(processHandle);
            }
            success = true;
            return new WindowsJobObject(job, required, committedMemoryLimitBytes,
                    "applied: kill-on-close, active-process=32, process/job committed-memory limit");
        } finally {
            if (!success && !invalid(job)) {
                // failedAfterClose already closed it; CloseHandle is harmlessly avoided by nulling only there.
                // The helper cannot mutate the local handle, so check validity through the native value.
            }
        }
    }

    JobSnapshot snapshot() {
        if (invalid(jobHandle)) {
            return new JobSnapshot(false, required, committedMemoryLimitBytes, 0L, 0L, status);
        }
        JobObjectExtendedLimitInformation limits = new JobObjectExtendedLimitInformation();
        if (!Kernel32Job.INSTANCE.QueryInformationJobObject(jobHandle,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, limits.getPointer(), limits.size(), Pointer.NULL)) {
            return new JobSnapshot(true, required, committedMemoryLimitBytes, 0L, 0L,
                    status + "; peak query failed with Win32 error " + Native.getLastError());
        }
        limits.read();
        return new JobSnapshot(true, required, committedMemoryLimitBytes,
                limits.PeakJobMemoryUsed.longValue(), limits.PeakProcessMemoryUsed.longValue(), status);
    }

    @Override
    public void close() {
        if (!invalid(jobHandle)) {
            Kernel32Job.INSTANCE.CloseHandle(jobHandle);
        }
    }

    private static WindowsJobObject failed(boolean required, long limit, String message) throws IOException {
        if (required) {
            throw new IOException(message);
        }
        return new WindowsJobObject(null, false, limit, "not applied: " + message);
    }

    private static WindowsJobObject failedAfterClose(HANDLE job, boolean required, long limit, String message)
            throws IOException {
        Kernel32Job.INSTANCE.CloseHandle(job);
        return failed(required, limit, message);
    }

    private static boolean invalid(HANDLE handle) {
        if (handle == null || handle.getPointer() == null) {
            return true;
        }
        long value = Pointer.nativeValue(handle.getPointer());
        return value == 0L || value == -1L;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    record JobSnapshot(boolean applied, boolean required, long limitBytes,
            long peakJobCommittedBytes, long peakProcessCommittedBytes, String status) {
    }

    private interface Kernel32Job extends StdCallLibrary {
        Kernel32Job INSTANCE = Native.load("kernel32", Kernel32Job.class);

        HANDLE CreateJobObjectW(SECURITY_ATTRIBUTES attributes, WString name);

        boolean SetInformationJobObject(HANDLE job, int informationClass, Pointer information, int length);

        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);

        boolean QueryInformationJobObject(HANDLE job, int informationClass, Pointer information,
                int length, Pointer returnLength);

        HANDLE OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean CloseHandle(HANDLE handle);
    }

    @Structure.FieldOrder({"PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
            "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit", "Affinity",
            "PriorityClass", "SchedulingClass"})
    public static final class JobObjectBasicLimitInformation extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public SIZE_T MinimumWorkingSetSize = new SIZE_T();
        public SIZE_T MaximumWorkingSetSize = new SIZE_T();
        public int ActiveProcessLimit;
        public ULONG_PTR Affinity = new ULONG_PTR();
        public int PriorityClass;
        public int SchedulingClass;
    }

    @Structure.FieldOrder({"ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
            "ReadTransferCount", "WriteTransferCount", "OtherTransferCount"})
    public static final class IoCounters extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;
    }

    @Structure.FieldOrder({"BasicLimitInformation", "IoInfo", "ProcessMemoryLimit", "JobMemoryLimit",
            "PeakProcessMemoryUsed", "PeakJobMemoryUsed"})
    public static final class JobObjectExtendedLimitInformation extends Structure {
        public JobObjectBasicLimitInformation BasicLimitInformation = new JobObjectBasicLimitInformation();
        public IoCounters IoInfo = new IoCounters();
        public SIZE_T ProcessMemoryLimit = new SIZE_T();
        public SIZE_T JobMemoryLimit = new SIZE_T();
        public SIZE_T PeakProcessMemoryUsed = new SIZE_T();
        public SIZE_T PeakJobMemoryUsed = new SIZE_T();
    }
}
