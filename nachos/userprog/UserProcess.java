package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		int numPhysPages = Machine.processor().getNumPhysPages();
		pageTable = new TranslationEntry[numPhysPages];
		for (int i = 0; i < numPhysPages; i++) // 一开始所有内存都是不可用的
			pageTable[i] = new TranslationEntry(i, i, false, false, false, false);
		gottenFrameList = new LinkedList<>();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 *
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 *
	 * @param name
	 *            the name of the file containing the executable.
	 * @param args
	 *            the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		// 在进程开始执行前，给它添加到标准输入和标准输出的文件描述符
		fileDescriptors.put(0, UserKernel.console.openForReading());
		fileDescriptors.put(1, UserKernel.console.openForWriting());
		UThread t = new UThread(this);
		UserKernel.manageNewProcess(pid, t);
		t.setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 *
	 * @param vaddr
	 *            the starting virtual address of the null-terminated string.
	 * @param maxLength
	 *            the maximum number of characters in the string, not including
	 *            the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 *         found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr
	 *            the first byte of virtual memory to read.
	 * @param data
	 *            the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 *
	 * @param vaddr
	 *            the first byte of virtual memory to read.
	 * @param data
	 *            the array where the data will be stored.
	 * @param offset
	 *            the first byte to write in the array.
	 * @param length
	 *            the number of bytes to transfer from virtual memory to the
	 *            array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// // for now, just assume that virtual addresses equal physical
		// addresses
		// if (vaddr < 0 || vaddr >= memory.length)
		// return 0;

		// 将虚拟地址转换为物理地址
		if (vaddr < 0)
			return 0;
		TranslationEntry entry = pageTable[Processor.pageFromAddress(vaddr)];
		int ppn = entry.ppn; // 根据页号查页表，得到物理页号
		int paddr = Processor.makeAddress(ppn, Processor.offsetFromAddress(vaddr));
		if (paddr >= memory.length)
			return 0;

		int amount = Math.min(length, memory.length - paddr);
		System.arraycopy(memory, paddr, data, offset, amount);

		return amount;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr
	 *            the first byte of virtual memory to write.
	 * @param data
	 *            the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 *
	 * @param vaddr
	 *            the first byte of virtual memory to write.
	 * @param data
	 *            the array containing the data to transfer.
	 * @param offset
	 *            the first byte to transfer from the array.
	 * @param length
	 *            the number of bytes to transfer from the array to virtual
	 *            memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// // for now, just assume that virtual addresses equal physical
		// addresses
		// if (vaddr < 0 || vaddr >= memory.length)
		// return 0;

		// 将虚拟地址转换为物理地址
		if (vaddr < 0)
			return 0;
		TranslationEntry entry = pageTable[Processor.pageFromAddress(vaddr)];
		int ppn = entry.ppn; // 根据页号查页表，得到物理页号
		int paddr = Processor.makeAddress(ppn, Processor.offsetFromAddress(vaddr));
		if (paddr >= memory.length)
			return 0;

		int amount = Math.min(length, memory.length - paddr);
		System.arraycopy(data, offset, memory, paddr, amount);

		return amount;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 *
	 * @param name
	 *            the name of the file containing the executable.
	 * @param args
	 *            the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		} catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections() || allocatePage(numPages - 1) == -1)
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	private int allocatePage(int vpn) {
		// 从物理内存中得到需要的页号
		UserKernel.globalFFLLock.acquire();
		if (UserKernel.freeFrameList.isEmpty()) {
			UserKernel.globalFFLLock.release();
			Lib.debug(dbgProcess, "Free Frame List Empty!");
			return -1;
		}
		int page = UserKernel.freeFrameList.removeFirst();
		UserKernel.globalFFLLock.release();
		gottenFrameList.add(page);
		pageTable[vpn].ppn = page; // 设置页表对应条目的物理页号
		pageTable[vpn].valid = true; // 设置页表对应条目为有效
		return page;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 *
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess,
					"\tinitializing " + section.getName() + " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;

				// // for now, just assume virtual addresses=physical addresses
				// section.loadPage(i, vpn);
				// 从物理内存中得到需要的页号
				UserKernel.globalFFLLock.acquire();
				if (UserKernel.freeFrameList.isEmpty()) {
					UserKernel.globalFFLLock.release();
					Lib.debug(dbgProcess, "Can't get enough pages for section!");
					return false;
				}
				int frame = UserKernel.freeFrameList.removeFirst();
				UserKernel.globalFFLLock.release();
				section.loadPage(i, frame); // 将section加载到对应页
				pageTable[vpn].ppn = frame; // 设置页表对应条目的物理页号
				pageTable[vpn].valid = true; // 设置页表对应条目为有效
				pageTable[vpn].readOnly = section.isReadOnly(); // 设置是否只读
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\treleasing " + section.getName() + " section (" + section.getLength() + " pages)");
			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				UserKernel.globalFFLLock.acquire();
				UserKernel.freeFrameList.add(pageTable[vpn].ppn);
				UserKernel.globalFFLLock.release();
			}
		}
	}

	private void releasePages() {
		// 释放所有占有的页
		UserKernel.globalFFLLock.acquire();
		for (int vpn : gottenFrameList)
			UserKernel.freeFrameList.add(pageTable[vpn].ppn);
		UserKernel.globalFFLLock.release();
		UserKernel.waitingLock.acquire();
		for (KThread t : UserKernel.waitingBecauseOfPageFault) // 唤醒所有因内存不足而沉睡的线程
			t.ready();
		UserKernel.waitingLock.release();
	}

	private void closeAllFile() {
		for (HashMap.Entry<Integer, OpenFile> entry : fileDescriptors.entrySet())
			entry.getValue().close();
	}

	// 再次执行之前的指令，处理PageFault时用到
	private void redoInstruction(Processor processor) {
		processor.writeRegister(Processor.regNextPC, processor.readRegister(Processor.regPC));
		processor.advancePC();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	public boolean isRootProcess() {
		return pid == 0;
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {
		// 如果不是由root进程调用，忽略该请求并立刻返回-1，表示失败
		if (!isRootProcess())
			return -1;

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	/**
	 * Handle the exit(int status) system call OR a process exits abnormally.
	 */
	private int handleExit(int status) {
		Lib.debug(dbgProcess, "Process " + pid + " exits with status " + status);
		exitStatus = status;
		closeAllFile();
		unloadSections();
		releasePages();
		UserKernel.removeExitedProcess(pid);
		if (UserKernel.allProcessExited())
			Kernel.kernel.terminate();
		UThread.finish();
		return 0;
	}

	/**
	 * Handle the exec(char *name, int argc, char **argv) system call.
	 */
	private int handleExec(int pointOfName, int argc, int pointOfArgv) {
		if (pointOfName <= 0 || argc < 1 || pointOfArgv <= 0)
			return -1; // 参数不正确
		String name = readVirtualMemoryString(pointOfName, ARG_STR_MAX_LENGTH);
		if (name == null)
			return -1; // 无法获得文件名
		String[] argv = new String[argc];
		byte[] pointOfArgvByte = new byte[4];
		Lib.assertTrue(readVirtualMemory(pointOfArgv, pointOfArgvByte) == 4);
		int point = Lib.bytesToInt(pointOfArgvByte, 0);
		for (int i = 0; i < argc; i++) {
			argv[i] = readVirtualMemoryString(point, ARG_STR_MAX_LENGTH);
			if (argv[i] == null) // 无法读取到以null结尾的字符串，说明出了错误
				return -1;
			point+=argv[i].length()+1;
		}
		UserProcess childProcess = UserProcess.newUserProcess();
		if (!childProcess.execute(name, argv)) // 未成功执行
			return -1;
		childrenId.add(childProcess.pid);
		return childProcess.pid;
	}

	/**
	 * Handle the join(int pid, int *status) system call.
	 */
	private int handleJoin(int pid, int pointOfStatus) {
		if (pointOfStatus <= 0 || !childrenId.contains(pid))
			return -1;
		KThread child = UserKernel.getThreadByPID(pid);
		child.join();
		int status = ((UThread) child).process.exitStatus;
		Lib.assertTrue(writeVirtualMemory(pointOfStatus, Lib.bytesFromInt(status)) == 4);
		return status == 0 ? 1 : 0; // 如果子进程正常退出，返回1，否则返回0
	}

	/**
	 * Handle the creat(char *name) system call.
	 */
	private int handleCreat(int pointOfName) {
		if (pointOfName <= 0)
			return -1; // 指针指向的位置不合法
		String name = readVirtualMemoryString(pointOfName, ARG_STR_MAX_LENGTH);
		if (name == null)
			return -1; // 无法获得文件名
		OpenFile f = UserKernel.fileSystem.open(name, true);
		if (f == null)
			return -1; // 无法打开文件
		int fd = openCount++;
		if (fileDescriptors.containsKey(fd)) // 已经存在该文件描述符
			return -1;
		fileDescriptors.put(fd, f);
		return fd;
	}

	/**
	 * Handle the open(char *name) system call.
	 */
	private int handleOpen(int pointOfName) {
		if (pointOfName <= 0)
			return -1; // 指针指向的位置不合法
		String name = readVirtualMemoryString(pointOfName, ARG_STR_MAX_LENGTH);
		if (name == null)
			return -1; // 无法获得文件名
		OpenFile f = UserKernel.fileSystem.open(name, false);
		if (f == null)
			return -1; // 无法打开文件
		int fd = openCount++;
		if (fileDescriptors.containsKey(fd)) // 已经存在该文件描述符
			return -1;
		fileDescriptors.put(fd, f);
		return fd;
	}

	/**
	 * Handle the read(int fd, char *buffer, int size) system call.
	 */
	private int handleRead(int fd, int pointOfBuffer, int size) {
		if (fd < 0 || pointOfBuffer <= 0 || size < 0)
			return -1; // 参数不合法
		OpenFile f = fileDescriptors.get(fd);
		if (f == null)
			return -1; // 没有打开文件描述符所述的文件
		byte[] buf = new byte[size];
		int amount = f.read(buf, 0, size); // 将读取到的内容从buf的0位置开始放
		if (amount == -1)
			return -1;
		Lib.assertTrue(writeVirtualMemory(pointOfBuffer, buf, 0, amount) == amount); // 将buf中的有效内容写入到指针所指向的内存中
		return amount;
	}

	/**
	 * Handle the write(int fd, char *buffer, int size) system call.
	 */
	private int handleWrite(int fd, int pointOfBuffer, int size) {
		if (fd < 0 || pointOfBuffer <= 0 || size < 0)
			return -1; // 参数不合法
		OpenFile f = fileDescriptors.get(fd);
		if (f == null)
			return -1; // 没有打开文件描述符所述的文件
		byte[] buf = new byte[size];
		Lib.assertTrue(readVirtualMemory(pointOfBuffer, buf) == size); // 从指针所指向的位置读取到size个字节
		int amount = f.write(buf, 0, size);
		if (amount != size) // 如果没有写入指定的字节数，说明发生了错误
			return -1;
		return amount;
	}

	/**
	 * Handle the close(int fd) system call.
	 */
	private int handleClose(int fd) {
		if (fd < 0)
			return -1; // 文件描述符不合法
		OpenFile f = fileDescriptors.remove(fd);
		if (f == null)
			return -1; // 没有打开文件描述符所述的文件
		f.close();
		return 0;
	}

	/**
	 * Handle the unlink(char *name) system call.
	 */
	private int handleUnlink(int pointOfName) {
		if (pointOfName <= 0)
			return -1; // 指针指向的位置不合法
		String name = readVirtualMemoryString(pointOfName, ARG_STR_MAX_LENGTH);
		if (name == null)
			return -1; // 无法获得文件名
		if (!UserKernel.fileSystem.remove(name)) // 删除文件失败，则返回-1
			return -1;
		return 0;
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2, syscallJoin = 3, syscallCreate = 4,
			syscallOpen = 5, syscallRead = 6, syscallWrite = 7, syscallClose = 8, syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 *
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 *								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 *								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall
	 *            the syscall number.
	 * @param a0
	 *            the first syscall argument.
	 * @param a1
	 *            the second syscall argument.
	 * @param a2
	 *            the third syscall argument.
	 * @param a3
	 *            the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			return handleExit(a0);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);
		case syscallCreate:
			return handleCreat(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);

		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by
	 * <tt>UserKernel.exceptionHandler()</tt>. The <i>cause</i> argument
	 * identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 *
	 * @param cause
	 *            the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0), processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1), processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;
		case Processor.exceptionPageFault:
			int vpn = Processor.pageFromAddress(processor.readRegister(Processor.regBadVAddr));
			while (allocatePage(vpn) == -1) {
				Lib.debug(dbgProcess, "Process " + pid + " is suspended because there are no free pages.");
				UserKernel.waitingLock.acquire();
				UserKernel.waitingBecauseOfPageFault.add(UThread.currentThread());
				UserKernel.waitingLock.release();
				UThread.sleep();
			}
			redoInstruction(processor);
			break;
		case Processor.exceptionTLBMiss:
		case Processor.exceptionReadOnly:
		case Processor.exceptionBusError:
		case Processor.exceptionAddressError:
		case Processor.exceptionOverflow:
		case Processor.exceptionIllegalInstruction:
			handleExit(cause); // 停止当前线程
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: " + Processor.exceptionNames[cause]);
			Lib.assertNotReached("Unexpected exception");
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;
	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;
	private int argc, argv;

	private HashSet<Integer> childrenId = new HashSet<>();
	private int exitStatus;

	/** Every process has a process id. */
	private final int pid = numCreated++;
	/** Number of times the UserProcess constructor was called. */
	private static int numCreated = 0;

	/**
	 * The maximum length of for strings passed as arguments to system calls is
	 * 256 bytes.
	 */
	private static final int ARG_STR_MAX_LENGTH = 256;

	/** The file numbers of which has opened. Only increment. */
	private int openCount = 2;
	private HashMap<Integer, OpenFile> fileDescriptors = new HashMap<>();

	private LinkedList<Integer> gottenFrameList;

	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';
}
