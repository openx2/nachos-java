package nachos.threads;

import nachos.machine.*;
import nachos.threads.PriorityScheduler.ThreadState;

import java.util.LinkedList;
import java.util.HashSet;

/**
 * A scheduler that chooses threads based on their priorities.
 *
 * <p>
 * A priority scheduler associates a priority with each thread. The next thread
 * to be dequeued is always a thread with priority no less than any other
 * waiting thread's priority. Like a round-robin scheduler, the thread that is
 * dequeued is, among all the threads of the same (highest) priority, the thread
 * that has been waiting longest.
 *
 * <p>
 * Essentially, a priority scheduler gives access in a round-robin fassion to
 * all the highest-priority threads, and ignores all other threads. This has the
 * potential to starve a thread if there's always a thread waiting with higher
 * priority.
 *
 * <p>
 * A priority scheduler must partially solve the priority inversion problem; in
 * particular, priority must be donated through locks, and through joins.
 */
public class PriorityScheduler extends Scheduler {
	/**
	 * Allocate a new priority scheduler.
	 */
	public PriorityScheduler() {
	}

	/**
	 * Allocate a new priority thread queue.
	 *
	 * @param transferPriority
	 *            <tt>true</tt> if this queue should transfer priority from
	 *            waiting threads to the owning thread.
	 * @return a new priority thread queue.
	 */
	public ThreadQueue newThreadQueue(boolean transferPriority) {
		return new PriorityQueue(transferPriority);
	}

	public int getPriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getThreadState(thread).getPriority();
	}

	public int getEffectivePriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getThreadState(thread).getEffectivePriority();
	}

	public void setPriority(KThread thread, int priority) {
		Lib.assertTrue(Machine.interrupt().disabled());

		Lib.assertTrue(priority >= priorityMinimum && priority <= priorityMaximum);

		getThreadState(thread).setPriority(priority);
	}

	public boolean increasePriority() {
		boolean intStatus = Machine.interrupt().disable();

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMaximum)
			return false;

		setPriority(thread, priority + 1);

		Machine.interrupt().restore(intStatus);
		return true;
	}

	public boolean decreasePriority() {
		boolean intStatus = Machine.interrupt().disable();

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMinimum)
			return false;

		setPriority(thread, priority - 1);

		Machine.interrupt().restore(intStatus);
		return true;
	}

	// 测试effective priority是否有效
	public static void selfTest() {
		Lock resource = new Lock();
		Runnable r1 = new Runnable() {
			@Override
			public void run() {
				resource.acquire();
				KThread.yield();
				System.out.println("I'm T1.");
				resource.release();
			}
		};
		Runnable r2 = new Runnable() {
			@Override
			public void run() {
				KThread.yield();
				System.out.println("I'm T2.");
			}
		};
		KThread t1 = new KThread(r1).setName("T1");
		KThread t2 = new KThread(r2).setName("T2");
		boolean status = Machine.interrupt().disable();
		((PriorityScheduler) ThreadedKernel.scheduler).setPriority(t1, 5);
		((PriorityScheduler) ThreadedKernel.scheduler).setPriority(t2, 4);
		Machine.interrupt().restore(status);
		// 认为main线程是T3，开始运行
		resource.acquire();
		t2.fork(); // T2再运行，打断T3
		t1.fork(); // T1再运行，打断T2
		KThread.yield();
		System.out.println("I'm T3.");
		resource.release();
		ThreadedKernel.alarm.waitUntil(1000);
	}

	/**
	 * The default priority for a new thread. Do not change this value.
	 */
	public static final int priorityDefault = 1;
	/**
	 * The minimum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMinimum = 0;
	/**
	 * The maximum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMaximum = 7;

	/**
	 * Return the scheduling state of the specified thread.
	 *
	 * @param thread
	 *            the thread whose scheduling state to return.
	 * @return the scheduling state of the specified thread.
	 */
	protected ThreadState getThreadState(KThread thread) {
		if (thread.schedulingState == null)
			thread.schedulingState = new ThreadState(thread);

		return (ThreadState) thread.schedulingState;
	}

	/**
	 * A <tt>ThreadQueue</tt> that sorts threads by priority.
	 */
	protected class PriorityQueue extends ThreadQueue {
		PriorityQueue(boolean transferPriority) {
			this.transferPriority = transferPriority;
		}

		public void waitForAccess(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).waitForAccess(this);
		}

		public void acquire(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).acquire(this);
		}

		public KThread nextThread() {
			Lib.assertTrue(Machine.interrupt().disabled());
			
			if (resourceHolder != null) { // 当资源被释放，下一个线程开始执行时，如果当前资源持有者不为null
				ThreadState ts = getThreadState(resourceHolder);
				//如果maxEffectivePriorityTS为资源持有者，那么它的线程应该不在等待队列中
				//所以在这里把maxEffectivePriorityTS设为空
				if (maxEffectivePriorityTS == ts)
					maxEffectivePriorityTS = null;
				ts.gottenResources.remove(this); // 从resourceHolder持有资源的队列中删除当前队列
				ts.setEffectivePriority(ts.priority); // 让当前资源持有者的实际优先级变回本身的优先级
				resourceHolder = null;
			}
			if (waitQueue.isEmpty())
				return null;

			KThread next = pickNextThread();
			if(!waitQueue.remove(next)) {
				maxEffectivePriorityTS = null;
				next = pickNextThread();
				waitQueue.remove(next);
			}
			return next;
		}

		/**
		 * Return the next thread that <tt>nextThread()</tt> would return,
		 * without modifying the state of this queue.
		 *
		 * @return the next thread that <tt>nextThread()</tt> would return.
		 */
		protected KThread pickNextThread() {
			if (waitQueue.isEmpty())
				return null;

			if (transferPriority && maxEffectivePriorityTS != null)
				return maxEffectivePriorityTS.thread;
			
			KThread next = waitQueue.getFirst(); // 先得到等待队列中的第一个线程
			for (KThread t : waitQueue) {
				ThreadState ts = getThreadState(t);
				if (transferPriority) { // 如果transferPriority为true，那么比较实际优先级
					if (ts.getEffectivePriority() > getThreadState(next).getEffectivePriority())
						next = ts.thread;
				} else { // 否则比较本身的优先级
					if (ts.getPriority() > getThreadState(next).getPriority())
						next = ts.thread;
				}
			}
			return next;
		}

		public void print() {
			Lib.assertTrue(Machine.interrupt().disabled());
			for (KThread t : waitQueue)
				System.out.print((KThread) t + " ");
			System.out.println();
		}

		/**
		 * <tt>true</tt> if this queue should transfer priority from waiting
		 * threads to the owning thread.
		 */
		public boolean transferPriority;
		private LinkedList<KThread> waitQueue = new LinkedList<>();
		private KThread resourceHolder = null;
		private ThreadState maxEffectivePriorityTS = null;
	}

	/**
	 * The scheduling state of a thread. This should include the thread's
	 * priority, its effective priority, any objects it owns, and the queue it's
	 * waiting for, if any.
	 *
	 * @see nachos.threads.KThread#schedulingState
	 */
	protected class ThreadState {
		/**
		 * Allocate a new <tt>ThreadState</tt> object and associate it with the
		 * specified thread.
		 *
		 * @param thread
		 *            the thread this state belongs to.
		 */
		public ThreadState(KThread thread) {
			this.thread = thread;

			setPriority(priorityDefault);
		}

		/**
		 * Return the priority of the associated thread.
		 *
		 * @return the priority of the associated thread.
		 */
		public int getPriority() {
			return priority;
		}

		/**
		 * Return the effective priority of the associated thread.
		 *
		 * @return the effective priority of the associated thread.
		 */
		public int getEffectivePriority() {
			return effectivePriority;
		}

		/**
		 * Set the priority of the associated thread to the specified value.
		 *
		 * @param priority
		 *            the new priority.
		 */
		public void setPriority(int priority) {
			if (this.priority == priority)
				return;

			this.priority = priority;

			setEffectivePriority(priority);
		}

		public void setEffectivePriority(int effectivePriority) {
			if (effectivePriority < priority) // 当实际优先级小于优先级时，设置无意义
				return;
			// 当新的实际优先级大于原来的实际优先级时，将自己的实际优先级设置为它，并检查所有自己正在等待的资源，把优先级捐献给它们
			if (effectivePriority > this.effectivePriority) {
				this.effectivePriority = effectivePriority;
				for (PriorityQueue q : waitingResources) {
					if (q.resourceHolder != null
							&& getThreadState(q.resourceHolder).getEffectivePriority() < effectivePriority) {
						q.maxEffectivePriorityTS = this; //自己要对q进行捐献
						getThreadState(q.resourceHolder).setEffectivePriority(effectivePriority);
					}
				}
			} else if (effectivePriority == this.effectivePriority) {// 当新的实际优先级等于原来的时，不用进行操作
				return;
			} else {// 当新的实际优先级小于原来的时，从自己持有的资源中找到最大实际优先级，将它设为自己的实际优先级
				for (PriorityQueue q : gottenResources) {
					KThread t = q.pickNextThread();
					if (t != null && t != this.thread && effectivePriority < getThreadState(t).getEffectivePriority())
						effectivePriority = getThreadState(t).getEffectivePriority();
				}
				if (effectivePriority == this.effectivePriority) // 在能够找到实际优先级等于原来的情况下，不用进行下一步操作
					return;
				int preEffectivePriority = this.effectivePriority;
				this.effectivePriority = effectivePriority;
				for (PriorityQueue q : waitingResources) {
					// 把当前线程所等待资源的队列的集合中的实际优先级与原来的effectivePriority相等的筛选出来
					// 并去除resourceHolder为空的队列
					// 目的是找出最高实际优先级时由当前的线程所捐献的队列，对它进行运算
					if (q.resourceHolder != null
							&& getThreadState(q.resourceHolder).getEffectivePriority() == preEffectivePriority)
						getThreadState(q.resourceHolder).setEffectivePriority(effectivePriority);
					if (q.maxEffectivePriorityTS == this)
						q.maxEffectivePriorityTS = null; //有最大实际优先级的线程失效了
				}
			}
		}

		/**
		 * Called when <tt>waitForAccess(thread)</tt> (where <tt>thread</tt> is
		 * the associated thread) is invoked on the specified priority queue.
		 * The associated thread is therefore waiting for access to the resource
		 * guarded by <tt>waitQueue</tt>. This method is only called if the
		 * associated thread cannot immediately obtain access.
		 *
		 * @param waitQueue
		 *            the queue that the associated thread is now waiting on.
		 *
		 * @see nachos.threads.ThreadQueue#waitForAccess
		 */
		public void waitForAccess(PriorityQueue waitQueue) {
			Lib.assertTrue(Machine.interrupt().disabled());
			waitQueue.waitQueue.add(thread);
			// 存在持有本队列资源的进程
			if (waitQueue.resourceHolder != null) {
				if (getThreadState(waitQueue.resourceHolder).getEffectivePriority() < effectivePriority) {
					waitQueue.maxEffectivePriorityTS = this; //自己要对waitQueue进行捐献
					getThreadState(waitQueue.resourceHolder).setEffectivePriority(effectivePriority);
				}
				waitingResources.add(waitQueue);
			}
		}

		/**
		 * Called when the associated thread has acquired access to whatever is
		 * guarded by <tt>waitQueue</tt>. This can occur either as a result of
		 * <tt>acquire(thread)</tt> being invoked on <tt>waitQueue</tt> (where
		 * <tt>thread</tt> is the associated thread), or as a result of
		 * <tt>nextThread()</tt> being invoked on <tt>waitQueue</tt>.
		 *
		 * @see nachos.threads.ThreadQueue#acquire
		 * @see nachos.threads.ThreadQueue#nextThread
		 */
		public void acquire(PriorityQueue waitQueue) {
			Lib.assertTrue(Machine.interrupt().disabled());

			Lib.assertTrue(waitQueue.waitQueue.isEmpty()); // 断言当前等待队列为空
			Lib.assertTrue(waitQueue.resourceHolder == null); // 当前没有资源持有者
			waitQueue.resourceHolder = thread; // 确认当前线程为资源持有者
			waitQueue.maxEffectivePriorityTS = this; //让自己成为有最大实际优先级的进程
			waitingResources.remove(waitQueue); // 得到了资源，从表示正在等待的资源的集合中删除该资源
			gottenResources.add(waitQueue); // 当前线程得到了该队列的资源，保存该队列的引用
		}

		/** The thread with which this object is associated. */
		protected KThread thread;
		/** The priority of the associated thread. */
		protected int priority;
		/** The effective priority of the associated thread, used for cache. */
		protected int effectivePriority;
		/** The queues whose resources hold by this thread. */
		private HashSet<PriorityQueue> gottenResources = new HashSet<>();
		/** The queues whose resources wait by this thread. */
		private HashSet<PriorityQueue> waitingResources = new HashSet<>();
	}
}
