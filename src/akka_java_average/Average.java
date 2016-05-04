/**
 * Author: Mido Assran
 * Date: Tue, May 3, 2016
 * Description:
 * 		This creates an abstract network of actors where 
 * 		actors work together to compute the average
 * 		of their abstract-network IDs.
*/


package akka_java_average;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.Inbox;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import akka.routing.ActorRefRoutee;
import akka.routing.RoundRobinRoutingLogic;
import akka.routing.Router;
import akka.routing.Routee;

import java.util.concurrent.TimeUnit;

import com.mcgill.mido.java.Pi.Worker;

public class Average {
	
	/*
	 * Everything starts at main!
	 */
	public static void main(String[] args) {
		Average avg = new Average();
		int numWorkers = 2;
		avg.calculate(numWorkers);
	}
	
	public void calculate(final int nrOfWorkers) {
		// Create an AKKA system
		ActorSystem system = ActorSystem.create("AvgSystem");

		// Create the result reporter, which will print the result and subsequently shutdown the machine
		final ActorRef reporter = system.actorOf(Props.create(Reporter.class), "reporter");

		// Create the node average computation coordinator
		ActorRef coordinator = system.actorOf(Props.create(Coordinator.class, nrOfWorkers, reporter), "coordinator");

		// start the calculation
		coordinator.tell(new StartAverage(), ActorRef.noSender());
	}
	
	
	
	/*------------------------------Messages-----------------------------------*
	 * StartAverage: 		Sent to coordinator node to start worker node computation
	 * SetPeer:				Sent to a worker node to set it's overlay-network peer
	 * Compute: 			Sent to a worker node to continue running computation
	 * Result: 				Sent from the worker to the coordinator actor containing the results
	 * 	   					of the workers' computation
	 * NodeAverage:		 	Sent from the coordinator actor to the reporter actor containing the final
	 *		    			average result and how long (time) the calculation took.
	 */
	static class StartAverage {}
	static class SetPeer {
		private final ActorRef peer;
		
		public SetPeer(ActorRef peer){
			this.peer = peer;
		}
		
		public ActorRef getPeer(){
			return peer;
		}
	}
	static class Compute {
		private double runningAverage;
		private int nodesHit;
		
		public Compute(double runningAverage, int nodesHit) {
			this.runningAverage = runningAverage;
			this.nodesHit = nodesHit;
		}
		
		public double getRunningAverage(){
			return runningAverage;
		}
		public int getNodesHit(){
			return nodesHit;
		}
	}
	static class Result {
		private double value;
		
		public Result (double value) {
			this.value = value;
		}
		
		public double getValue() {
			return value;
		}
	}
	static class NodeAverage {
		private final double nodeAverage;
		private final Duration duration;

		public NodeAverage(double nodeAverage, Duration duration) {
			this.nodeAverage = nodeAverage;
			this.duration = duration;
		}

		public double getNodeAverage() {
			return nodeAverage;
		}

		public Duration getDuration() {
			return duration;
		}
	}
	/*---------------------------END Messages-----------------------------------*/

	
	
	/*--------------------------------Actors-------------------------------------*
	 * Worker: 			nodes in the network whose id averages we try to compute
	 * Coordinator:		Root node that spawns workers & coordinates their id average computation
	 */
	public static class Worker extends UntypedActor {
		private final int uniqueId;
		private ActorRef peer;
		private final ActorRef coordinator;
		private boolean didComputeOnce = false;
				
		public Worker (int uniqueId, ActorRef coordinator){
			this.coordinator = coordinator;
			this.uniqueId = uniqueId;
		}
		
		public void onReceive(Object message) {
			if (message instanceof Compute) { // compute the running average and pass on to peer or if already contributed pass up to supervisor
				Compute computation = (Compute) message;
				if (!didComputeOnce){
					try {
						double newAverage = calculateAverageFor(computation.getRunningAverage(),computation.getNodesHit());
						int newNodesHit = computation.getNodesHit() + 1;
						peer.tell(new Compute(newAverage, newNodesHit), getSelf());
						System.out.println("\tNode index: " + newNodesHit + "\t UID: " + uniqueId);
					} catch (NullPointerException e) {
						// peer was not set
						System.out.println(e.getMessage());
					}
				} else {
					coordinator.tell(new Result(computation.getRunningAverage()), getSelf());
				}
			} else if (message instanceof SetPeer) { // set our peer
				SetPeer setRPC = (SetPeer) message;
				boolean status = setPeer(setRPC.getPeer()); // true if succeeds, false if fails
			} else {
				unhandled(message);
			}
		}

		
		private boolean didSetPeer = false; // a custom implementation to make subsequentPeer "final"
		private boolean setPeer(ActorRef peer){
			if (!didSetPeer) {
				this.peer = peer;
				didSetPeer = true;
			}
			return !didSetPeer;		// return true for peer was not set (success), false for peer was set (failure)
		}
		
		private double calculateAverageFor(double runningAverage, int nodesHit){
			didComputeOnce = true;
			double weightOld = (double)nodesHit/(double)(nodesHit + 1);
			double weightNew = (double)1/(double)(nodesHit + 1);
			double newAverage = (runningAverage*weightOld) + (uniqueId*weightNew);
			return newAverage;
		}
	}
	
	public static class Coordinator extends UntypedActor {
		
		private final int minUID = 12;
		private final int maxUID = 137;
		
		private final ActorRef reporter;
		private double nodeAverage = 0;
		private final long start = System.currentTimeMillis();				
		private List<ActorRef> workers;
		private List<Integer> workerIDs;
		public Coordinator (final int nrOfWorkers, ActorRef reporter) {
			this.reporter = reporter;
			
			// create the worker nodes
			workers = new ArrayList<ActorRef>();
			workerIDs = new ArrayList<Integer>();
			Random random = new Random();
			for (int i = 0; i < nrOfWorkers; i++) {
				
				// create a unique (random) ID for each node
				int uid;
				do { 
					uid = random.nextInt(maxUID - minUID + 1) + minUID; 
				} while (workerIDs.contains(uid));
				workerIDs.add(uid);
				
				ActorRef r = getContext().actorOf(Props.create(Worker.class, uid, getSelf()));
				workers.add(r);
			}

		    // set peers of worker nodes
		    for (int i = 0; i < nrOfWorkers; i++){
		    	  ActorRef worker = workers.get(i);
			      if (i != nrOfWorkers-1){
			    	  ActorRef peer = workers.get(i+1);
			    	  worker.tell(new SetPeer(peer), ActorRef.noSender());
			      } else { // last worker in our array; set peer to be first (circular)
			    	  ActorRef peer = workers.get(0);
			    	  worker.tell(new SetPeer(peer), ActorRef.noSender());
			      }
		    }
		    
		}

		public void onReceive(Object message) {
			if (message instanceof StartAverage) {
				ActorRef worker = workers.get(0);
				worker.tell(new Compute(0,0), getSelf());
			} else if (message instanceof Result) {
				Result result = (Result) message;
				nodeAverage += result.getValue();
				Duration duration = Duration.create(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
				reporter.tell(new NodeAverage(nodeAverage, duration), getSelf());
				getContext().stop(getSelf());
			} else {
				unhandled(message);
			}
		}
	}

	public static class Reporter extends UntypedActor {
		public void onReceive(Object message) {
			if (message instanceof NodeAverage) {
				NodeAverage nodeAverage = (NodeAverage) message;
				System.out.println(String.format("\n\tNode average: \t\t%s\n\tCalculation time: \t%s", nodeAverage.getNodeAverage(), nodeAverage.getDuration()));
				getContext().system().shutdown();
			} else {
				unhandled(message);
			}
		}
	}

}

