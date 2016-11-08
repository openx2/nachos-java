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

	// ����effective priority�Ƿ���Ч
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
		// ��Ϊmain�߳���T3����ʼ����
		resource.acquire();
		t2.fork(); // T2�����У����T3
		t1.fork(); // T1�����У����T2
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
			
			if (resourceHolder != null) { // ����Դ���ͷţ���һ���߳̿�ʼִ��ʱ�������ǰ��Դ�����߲�Ϊnull
				ThreadState ts = getThreadState(resourceHolder);
				//���maxEffectivePriorityTSΪ��Դ�����ߣ���ô�����߳�Ӧ�ò��ڵȴ�������
				//�����������maxEffectivePriorityTS��Ϊ��
				if (maxEffectivePriorityTS == ts)
					maxEffectivePriorityTS = null;
				ts.gottenResources.remove(this); // ��resourceHolder������Դ�Ķ�����ɾ����ǰ����
				ts.setEffectivePriority(ts.priority); // �õ�ǰ��Դ�����ߵ�ʵ�����ȼ���ر�������ȼ�
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
			
			KThread next = waitQueue.getFirst(); // �ȵõ��ȴ������еĵ�һ���߳�
			for (KThread t : waitQueue) {
				ThreadState ts = getThreadState(t);
				if (transferPriority) { // ���transferPriorityΪtrue����ô�Ƚ�ʵ�����ȼ�
					if (ts.getEffectivePriority() > getThreadState(next).getEffectivePriority())
						next = ts.thread;
				} else { // ����Ƚϱ�������ȼ�
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
			if (effectivePriority < priority) // ��ʵ�����ȼ�С�����ȼ�ʱ������������
				return;
			// ���µ�ʵ�����ȼ�����ԭ����ʵ�����ȼ�ʱ�����Լ���ʵ�����ȼ�����Ϊ��������������Լ����ڵȴ�����Դ�������ȼ����׸�����
			if (effectivePriority > this.effectivePriority) {
				this.effectivePriority = effectivePriority;
				for (PriorityQueue q : waitingResources) {
					if (q.resourceHolder != null
							&& getThreadState(q.resourceHolder).getEffectivePriority() < effectivePriority) {
						q.maxEffectivePriorityTS = this; //�Լ�Ҫ��q���о���
						getThreadState(q.resourceHolder).setEffectivePriority(effectivePriority);
					}
				}
			} else if (effectivePriority == this.effectivePriority) {// ���µ�ʵ�����ȼ�����ԭ����ʱ�����ý��в���
				return;
			} else {// ���µ�ʵ�����ȼ�С��ԭ����ʱ�����Լ����е���Դ���ҵ����ʵ�����ȼ���������Ϊ�Լ���ʵ�����ȼ�
				for (PriorityQueue q : gottenResources) {
					KThread t = q.pickNextThread();
					if (t != null && t != this.thread && effectivePriority < getThreadState(t).getEffectivePriority())
						effectivePriority = getThreadState(t).getEffectivePriority();
				}
				if (effectivePriority == this.effectivePriority) // ���ܹ��ҵ�ʵ�����ȼ�����ԭ��������£����ý�����һ������
					return;
				int preEffectivePriority = this.effectivePriority;
				this.effectivePriority = effectivePriority;
				for (PriorityQueue q : waitingResources) {
					// �ѵ�ǰ�߳����ȴ���Դ�Ķ��еļ����е�ʵ�����ȼ���ԭ����effectivePriority��ȵ�ɸѡ����
					// ��ȥ��resourceHolderΪ�յĶ���
					// Ŀ�����ҳ����ʵ�����ȼ�ʱ�ɵ�ǰ���߳������׵Ķ��У�������������
					if (q.resourceHolder != null
							&& getThreadState(q.resourceHolder).getEffectivePriority() == preEffectivePriority)
						getThreadState(q.resourceHolder).setEffectivePriority(effectivePriority);
					if (q.maxEffectivePriorityTS == this)
						q.maxEffectivePriorityTS = null; //�����ʵ�����ȼ����߳�ʧЧ��
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
			// ���ڳ��б�������Դ�Ľ���
			if (waitQueue.resourceHolder != null) {
				if (getThreadState(waitQueue.resourceHolder).getEffectivePriority() < effectivePriority) {
					waitQueue.maxEffectivePriorityTS = this; //�Լ�Ҫ��waitQueue���о���
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

			Lib.assertTrue(waitQueue.waitQueue.isEmpty()); // ���Ե�ǰ�ȴ�����Ϊ��
			Lib.assertTrue(waitQueue.resourceHolder == null); // ��ǰû����Դ������
			waitQueue.resourceHolder = thread; // ȷ�ϵ�ǰ�߳�Ϊ��Դ������
			waitQueue.maxEffectivePriorityTS = this; //���Լ���Ϊ�����ʵ�����ȼ��Ľ���
			waitingResources.remove(waitQueue); // �õ�����Դ���ӱ�ʾ���ڵȴ�����Դ�ļ�����ɾ������Դ
			gottenResources.add(waitQueue); // ��ǰ�̵߳õ��˸ö��е���Դ������ö��е�����
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
