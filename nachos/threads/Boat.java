package nachos.threads;

import java.util.HashSet;

import nachos.ag.BoatGrader;

public class Boat {
	static BoatGrader bg;
	static Lock lock;
	static Condition2 boat;
	static boolean boatInOahu;
	static boolean gameOver;
	static int adultsNumInOahu;
	static HashSet<KThread> childrenInMolokai;
	static KThread childWaitInBoat;
	static int childrenNumInOahu;

	public static void selfTest() {
		BoatGrader b = new BoatGrader();

		System.out.println("\n ***Testing Boats with only 2 children***");
		begin(0, 2, b);

//		System.out.println("\n ***Testing Boats with 2 children, 1 adult***");
//		begin(1, 2, b);

//		System.out.println("\n ***Testing Boats with 3 children, 3 adults***");
//		begin(3, 3, b);
	}

	public static void begin(int adults, int children, BoatGrader b) {
		// Store the externally generated autograder in a class
		// variable to be accessible by children.
		bg = b;

		if(adults > 0 && children == 1) {
			System.out.println("No solution");
			return;
		}
		
		// Instantiate global variables here
		lock = new Lock();
		boat = new Condition2(lock);
		boatInOahu = true;
		gameOver = false;
		adultsNumInOahu = adults;
		childrenNumInOahu = children;
		childWaitInBoat = null;
		childrenInMolokai = new HashSet<>();
		
		// Create threads here. See section 3.4 of the Nachos for Java
		// Walkthrough linked from the projects page.

//		Runnable r = new Runnable() {
//			public void run() {
//				SampleItinerary();
//			}
//		};
//		KThread t = new KThread(r);
//		t.setName("Sample Boat Thread");
//		t.fork();
		for(int i=0; i<adults; i++) {
			KThread t = new KThread(new Runnable() {
				@Override
				public void run() {
					AdultItinerary();
				}
			});
			t.setName("Adult " + (i+1));
			t.fork();
		}
		for(int i=0; i<children; i++) {
			KThread t = new KThread(new Runnable() {
				@Override
				public void run() {
					ChildItinerary();
				}
			});
			t.setName("Child " + (i+1));
			t.fork();
		}
		ThreadedKernel.alarm.waitUntil(2000);
	}

	static void AdultItinerary() {
		bg.initializeAdult(); // Required for autograder interface. Must be the
								// first thing called.
		// DO NOT PUT ANYTHING ABOVE THIS LINE.

		/*
		 * This is where you should put your solutions. Make calls to the
		 * BoatGrader to show that it is synchronized. For example:
		 * bg.AdultRowToMolokai(); indicates that an adult has rowed the boat
		 * across to Molokai
		 */
		//只考虑大人在Oahu岛的情况
		lock.acquire();
		//大人在孩子数量大于1或者船不在O岛情况下，等待
		while(childrenNumInOahu > 1 || !boatInOahu) {
			sleepOnBoat();
		}
		//System.out.println(KThread.currentThread().getName()+" rows to Molokai.");
		bg.AdultRowToMolokai();
		adultsNumInOahu--;
		boatInOahu = false;
		lock.release();
	}

	static void ChildItinerary() {
		bg.initializeChild(); // Required for autograder interface. Must be the
								// first thing called.
		// DO NOT PUT ANYTHING ABOVE THIS LINE.
		//在游戏还未结束前，不断运行
		while(!gameOver) {
			lock.acquire();
			//船在O岛时且当前小孩也在O岛时
			if(boatInOahu&&!childrenInMolokai.contains(KThread.currentThread())) {
				//在船上没有小孩时
				if(childWaitInBoat == null) {
					//特殊情况：一开始就只有一个小孩
					if (adultsNumInOahu == 0 && childrenNumInOahu == 1) {
						//System.out.println(KThread.currentThread().getName()+" rows to Molokai.");
						bg.ChildRowToMolokai();
						childrenNumInOahu--;
					}
					childWaitInBoat = KThread.currentThread();
					sleepOnBoat();
				}
				//在船上已经有了一个小孩时
				else {
					//开船让两个小孩都去M岛
					bg.ChildRowToMolokai();
					bg.ChildRideToMolokai();
					//System.out.println(KThread.currentThread().getName()+" rows to Molokai.");
					//System.out.println(childWaitInBoat.getName()+" rides to Molokai.");
					childrenInMolokai.add(KThread.currentThread());
					childrenInMolokai.add(childWaitInBoat);
					childrenNumInOahu-=2;
					boatInOahu = false;
					childWaitInBoat = null;
					//判断这次两个小孩离开后O岛上是否已经没有人了
					gameOver = adultsNumInOahu == 0 && childrenNumInOahu == 0;
				}
			}
			//船在M岛时且当前小孩也在M岛时
			else if(!boatInOahu&&childrenInMolokai.contains(KThread.currentThread())) {
				//开船前往O岛，并沉睡
				//System.out.println(KThread.currentThread().getName()+" rows to Oahu.");
				bg.ChildRowToOahu();
				childrenInMolokai.remove(KThread.currentThread());
				childrenNumInOahu++;
				boatInOahu = true;
				sleepOnBoat();
			}
			//其他情况下，等待
			else {
				sleepOnBoat();
			}
			lock.release();
		}
	}

	static void SampleItinerary() {
		// Please note that this isn't a valid solution (you can't fit
		// all of them on the boat). Please also note that you may not
		// have a single thread calculate a solution and then just play
		// it back at the autograder -- you will be caught.
		System.out.println("\n ***Everyone piles on the boat and goes to Molokai***");
		bg.AdultRowToMolokai();
		bg.ChildRideToMolokai();
		bg.AdultRideToMolokai();
		bg.ChildRideToMolokai();
	}

	//叫醒一个已经沉睡在boat上的线程并沉睡在boat上
	private static void sleepOnBoat() {
		boat.wake();
		boat.sleep();
	}
}
