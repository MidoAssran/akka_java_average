/**
 * Author: Mido Assran
 * Date: Tue, May 3, 2016
 * Description:
 * 	Computes the value of pi based on a series algorithm.
 * 	This is a basic starter Akka project followed from a tutorial
 * 	available at http://doc.akka.io/docs/akka/2.0.1/intro/getting-started-first-java.html
 * 
*/



package com.mcgill.mido.java;

import java.util.List;
import java.util.ArrayList;
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

public class Pi {

	public static void main(String[] args) {
		Pi pi = new Pi();
		pi.calculate(4,20000,15000);
	}

	/*---------Messages-------*
	 * Calculate: 		Sent to master to start calculation
	 *
	 * Work: 		Sent from master to worker actors containing the work assignment
	 *
	 * Result: 		Sent from the worker to the master actor containing the results
	 * 	   		of the worker's computation
	 *
	 * PiApproximation: 	Sent from the master actor to the listener actor containing the final
	 *		    	pi result and how long (time) the calculation took.
	*/
	static class Calculate {
	}

	static class Work {
		private final int start;
		private final int nrOfElements;

		public Work(int start, int nrOfElements) {
			this.start = start;
			this.nrOfElements = nrOfElements;
		}

		public int getStart() {
			return start;
		}

		public int getNrOfElements() {
			return nrOfElements;
		}
	}

	static class Result {
		private final double value;

		public Result(double value) {
			this.value = value;
		}

		public double getValue() {
			return value;
		}
	}

	static class PiApproximation {
		private final double pi;
		private final Duration duration;

		public PiApproximation(double pi, Duration duration) {
			this.pi = pi;
			this.duration = duration;
		}

		public double getPi() {
			return pi;
		}

		public Duration getDuration() {
			return duration;
		}

	}




	public static class Worker extends UntypedActor {

		public void onReceive(Object message) {
			if (message instanceof Work) {
				Work work = (Work) message;
				double result = calculatePiFor(work.getStart(), work.getNrOfElements());
				getSender().tell(new Result(result), getSelf());
			} else {
				unhandled(message);
			}
		}

		private double calculatePiFor(int start, int nrOfElements) {
			double acc = 0.0;
			for (int i = start * nrOfElements; i <= ((start+1) * nrOfElements -1); i++) {
				acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1);
			}

			return acc;
		}
	}


	public static class Master extends UntypedActor {


		private final int nrOfMessages;
		private final int nrOfElements;

		private double pi;
		private int nrOfResults;
		private final long start = System.currentTimeMillis();

		private final ActorRef listener;
//		private final ActorRef workerRouter;

		private final Router workerRouter;

		public Master(final int nrOfWorkers, int nrOfMessages, int nrOfElements, ActorRef listener) {
			this.nrOfMessages = nrOfMessages;
			this.nrOfElements = nrOfElements;
			this.listener = listener;

			List<Routee> routees = new ArrayList<Routee>();
		    for (int i = 0; i < nrOfWorkers; i++) {
		      ActorRef r = getContext().actorOf(Props.create(Worker.class));
		      getContext().watch(r);
		      routees.add(new ActorRefRoutee(r));
		    }
		    workerRouter = new Router(new RoundRobinRoutingLogic(), routees);

		}


		public void onReceive(Object message) {
			if (message instanceof Calculate) {
				for (int start=0; start < nrOfMessages; start++){
					workerRouter.route(new Work(start, nrOfElements), getSelf());
				}
			} else if (message instanceof Result) {
				Result result = (Result) message;
				pi += result.getValue();
				nrOfResults += 1;
				if (nrOfResults == nrOfMessages) {
					// send the result to the listener
					Duration duration = Duration.create(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
					listener.tell(new PiApproximation(pi,duration), getSelf());
					// Stops this actor and all its supervised children
					getContext().stop(getSelf());
				}
			} else {
				unhandled(message);
			}
		}
	}

	public static class Listener extends UntypedActor {
		public void onReceive(Object message) {
			if (message instanceof PiApproximation) {
				PiApproximation approximation = (PiApproximation) message;
				System.out.println(String.format("\n\tPi approximation: \t%s\n\tCalculation time: \t%s", approximation.getPi(), approximation.getDuration()));
				getContext().system().shutdown();
			} else {
				unhandled(message);
			}
		}
	}

	public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages) {
		// Create an AKKA system
		ActorSystem system = ActorSystem.create("PiSystem");

		// Create the result listener, which will print the result and subsequently shutdown the machine
		final ActorRef listener = system.actorOf(Props.create(Listener.class), "listener");

		// Create the master
		ActorRef master = system.actorOf(Props.create(Master.class, nrOfWorkers, nrOfMessages, nrOfElements, listener), "master");

		// start the calculation
		master.tell(new Calculate(), ActorRef.noSender());
	}

}
