package nachos.userprog;

import java.util.HashMap;
import java.util.LinkedList;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

/**
 * A kernel that can support multiple user processes.
 */
public class UserKernel extends ThreadedKernel {
	/**
	 * Allocate a new user kernel.
	 */
	public UserKernel() {
		super();
	}

	/**
	 * Initialize this kernel. Creates a synchronized console and sets the
	 * processor's exception handler.
	 */
	public void initialize(String[] args) {
		super.initialize(args);

		console = new SynchConsole(Machine.console());

		Machine.processor().setExceptionHandler(new Runnable() {
			public void run() {
				exceptionHandler();
			}
		});
		
		getShellProgramArgs(args);
		
		//将所有物理内存上的页号加入到空闲帧列表中
		for(int i=0; i<Machine.processor().getNumPhysPages(); i++) {
			freeFrameList.add(i);
		}
		
		globalFFLLock = new Lock();
		waitingLock = new Lock();
		processThreadLock = new Lock();
	}

	private void getShellProgramArgs(String[] sysArgs) {
		boolean isShellProgramArgs = false;
		String[] args = new String[sysArgs.length];
		int argc = 0;
		for (int i=0; i<sysArgs.length; ) {
			String arg = sysArgs[i++];
			if (arg.length() > 0) {
				if (arg.equals("-x")) {
					isShellProgramArgs = true;
				} else if (arg.charAt(0) == '-') {
					isShellProgramArgs = false;
				} else if (isShellProgramArgs) {
					args[argc++] = arg;
				}
			}
		}
		shellProgramArgs = new String[argc];
		System.arraycopy(args, 0, shellProgramArgs, 0, argc);
	}
	
	public static void manageNewProcess(int pid, KThread thread) {
		processThreadLock.acquire();
		pidToThread.put(pid, thread);
		processThreadLock.release();
	}
	
	public static void removeExitedProcess(int pid) {
		processThreadLock.acquire();
		pidToThread.remove(pid);
		processThreadLock.release();
	}
	
	public static KThread getThreadByPID(int pid) {
		processThreadLock.acquire();
		KThread t = pidToThread.get(pid);
		processThreadLock.release();
		return t;
	}

	public static boolean allProcessExited() {
		processThreadLock.acquire();
		boolean allProcessExited = pidToThread.isEmpty();
		processThreadLock.release();
		return allProcessExited;
	}
	
	/**
	 * Test the console device.
	 */
	public void selfTest() {
//		super.selfTest();

//		System.out.println("Testing the console device. Typed characters");
//		System.out.println("will be echoed until q is typed.");
//
//		char c;
//
//		do {
//			c = (char) console.readByte(true);
//			console.writeByte(c);
//		} while (c != 'q');
//
//		System.out.println("");
		
		if (scheduler instanceof LotteryScheduler)
			LotteryScheduler.selfTest();
	}

	/**
	 * Returns the current process.
	 *
	 * @return the current process, or <tt>null</tt> if no process is current.
	 */
	public static UserProcess currentProcess() {
		if (!(KThread.currentThread() instanceof UThread))
			return null;

		return ((UThread) KThread.currentThread()).process;
	}

	/**
	 * The exception handler. This handler is called by the processor whenever a
	 * user instruction causes a processor exception.
	 *
	 * <p>
	 * When the exception handler is invoked, interrupts are enabled, and the
	 * processor's cause register contains an integer identifying the cause of
	 * the exception (see the <tt>exceptionZZZ</tt> constants in the
	 * <tt>Processor</tt> class). If the exception involves a bad virtual
	 * address (e.g. page fault, TLB miss, read-only, bus error, or address
	 * error), the processor's BadVAddr register identifies the virtual address
	 * that caused the exception.
	 */
	public void exceptionHandler() {
		Lib.assertTrue(KThread.currentThread() instanceof UThread);

		UserProcess process = ((UThread) KThread.currentThread()).process;
		int cause = Machine.processor().readRegister(Processor.regCause);
		process.handleException(cause);
	}

	/**
	 * Start running user programs, by creating a process and running a shell
	 * program in it. The name of the shell program it must run is returned by
	 * <tt>Machine.getShellProgramName()</tt>.
	 *
	 * @see nachos.machine.Machine#getShellProgramName
	 */
	public void run() {
		super.run();

		UserProcess process = UserProcess.newUserProcess();

		String shellProgram = Machine.getShellProgramName();
		Lib.assertTrue(process.execute(shellProgram, shellProgramArgs));

		KThread.currentThread().finish();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
	}

	private String[] shellProgramArgs = null;

	/** Globally accessible reference to the synchronized console. */
	public static SynchConsole console;
	
	public static LinkedList<Integer> freeFrameList = new LinkedList<>();
	public static Lock globalFFLLock;
	public static LinkedList<KThread> waitingBecauseOfPageFault = new LinkedList<>();
	public static Lock waitingLock;	
	private static HashMap<Integer, KThread> pidToThread = new HashMap<>();
	private static Lock processThreadLock;

	// dummy variables to make javac smarter
	private static Coff dummy1 = null;
}
