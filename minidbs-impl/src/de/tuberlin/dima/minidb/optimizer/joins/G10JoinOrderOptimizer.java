package de.tuberlin.dima.minidb.optimizer.joins;

import java.util.ArrayList;
import java.util.HashMap;

import de.tuberlin.dima.minidb.optimizer.AbstractJoinPlanOperator;
import de.tuberlin.dima.minidb.optimizer.OptimizerPlanOperator;
import de.tuberlin.dima.minidb.optimizer.cardinality.CardinalityEstimator;
import de.tuberlin.dima.minidb.optimizer.joins.util.JoinOrderOptimizerUtils;
import de.tuberlin.dima.minidb.semantics.JoinGraphEdge;
import de.tuberlin.dima.minidb.semantics.Relation;
import de.tuberlin.dima.minidb.semantics.predicate.JoinPredicate;
import de.tuberlin.dima.minidb.semantics.predicate.JoinPredicateConjunct;

public class G10JoinOrderOptimizer implements JoinOrderOptimizer {

	private CardinalityEstimator estimator;


	public G10JoinOrderOptimizer(CardinalityEstimator estimator) {
		this.estimator = estimator;	
	}

	@Override
	public OptimizerPlanOperator findBestJoinOrder(Relation[] relations,
			JoinGraphEdge[] joins) {

		/* Array containing all plans
		 * position i contains all plans of size i relations
		 */
		ArrayList<G10Plan[]> plansArray = new ArrayList<G10Plan[]>();;

		
		// Initializing plansArray with all plans of size 1 relations (i.e. simple table accesses)
		G10Plan[] singleRelationPlans = new G10Plan[relations.length];
		
		for (int i = 0; i < relations.length; i++) {
			Relation relation = relations[i];
			
			int id = relation.getID();
			int relationBitmap = (int) Math.pow(2, id);
			int neighbourBitmap = 0;
			
			for (JoinGraphEdge join : joins) {
				if(join.getLeftNode().getID() == id)
					neighbourBitmap += Math.pow(2, join.getRightNode().getID());
				else if(join.getRightNode().getID() == id)
					neighbourBitmap += Math.pow(2, join.getLeftNode().getID());					
			}
			G10Plan plan = new G10Plan(relation, relationBitmap, neighbourBitmap);
			
			singleRelationPlans[i] = plan;
		}
		
		plansArray.add(singleRelationPlans);
		
		
		
		
		// Enumerate all plans of size 2, then 3, ... until all relations are used
		for(int size = 2; size <= relations.length; size ++) {
			
			
			// Group the new plans according to their relation bitmap to be able to keep only the best one more easily
			HashMap<Integer, ArrayList<G10Plan>> resultPlans = new HashMap<Integer, ArrayList<G10Plan>>();
			
			// Build plans as join of plans of size i and N-i
			for (int i = 1; i <= size - i; i++) {				
				
				G10Plan[] plansRight = plansArray.get(i-1);
				G10Plan[] plansLeft = plansArray.get(size - i -1);
				
			
				for(G10Plan planLeft : plansLeft) {
					for(G10Plan planRight : plansRight) {
						
						// Check if the 2 plans are neighbours
						if((planLeft.getNeighbourBitmap() & planRight.getRelationBitmap()) != 0) {
							
							// Check that the 2 plans have no relations in common
							if((planLeft.getRelationBitmap() & planRight.getRelationBitmap()) == 0) {
								
								
								// Merge the 2 plans
								int relationBitmap = planLeft.getRelationBitmap() | planRight.getRelationBitmap();
								int neighbourBitmap = (planLeft.getNeighbourBitmap() | planRight.getNeighbourBitmap()) &(~relationBitmap);
								
								
								// Compute the resulting predicate
								JoinPredicateConjunct joinPred = new JoinPredicateConjunct();
								for (JoinGraphEdge join : joins) {
									int idL = join.getLeftNode().getID();
									int idR = join.getRightNode().getID();
							
									
									// Check for predicates to add (both left-right & righ-left sides)
									if ((planLeft.getRelationBitmap() >> idL) %2 != 0 && (planRight.getRelationBitmap() >> idR) %2 != 0 ) {
											joinPred.addJoinPredicate(join.getJoinPredicate());
										
									} else if ((planRight.getRelationBitmap() >> idL) %2 != 0 && (planLeft.getRelationBitmap() >> idR) %2 != 0 ) {
											
										// Side-switching seems necessary.. apparently A = B is not equivalent to B = A
											joinPred.addJoinPredicate(join.getJoinPredicate().createSideSwitchedCopy());
									}
								}
								
								JoinPredicate filteredPred = JoinOrderOptimizerUtils.filterTwinPredicates(joinPred);
								
								
								// Finally create plan
								OptimizerPlanOperator planOLeft = planLeft.getPlan();
								OptimizerPlanOperator planORight = planRight.getPlan();
								
								AbstractJoinPlanOperator planOperator = new AbstractJoinPlanOperator(planOLeft, planORight, filteredPred);
							
								
								estimator.estimateJoinCardinality(planOperator);
								
								
								
								planOperator.setOperatorCosts(planORight.getOutputCardinality() + planOLeft.getOutputCardinality());
								
								planOperator.setCumulativeCosts(planOperator.getOperatorCosts() + planOLeft.getCumulativeCosts() + planORight.getCumulativeCosts());
							
								G10Plan plan = new G10Plan(planOperator, relationBitmap, neighbourBitmap);
								
								// Add it to the result HashMap
								if (resultPlans.containsKey(relationBitmap))
									resultPlans.get(relationBitmap).add(plan);
								else {
									ArrayList<G10Plan> planList = new ArrayList<G10Plan>();
									planList.add(plan);
									resultPlans.put(relationBitmap, planList);
									
								}
							}
						}						
					}				
				}				
			}
			
			
			// Remove "bad" plans			
			G10Plan[] goodPlans = filterPlans(resultPlans);
			
			plansArray.add(goodPlans);
				
		}
	
		
		
		
		//printCard(plansArray.get(plansArray.size() -1)[0].getPlan(), 0);
		
		// Return best plan
		
		return plansArray.get(plansArray.size() -1)[0].getPlan();
		

	}
	
	
	
	/* Filter plans contained in the HashMap
	 * Each key (relation bitmap) refers to plans using the same relations.
	 * We only keep the best plan for each different relation bitmap
	 */
	private G10Plan[] filterPlans(HashMap<Integer, ArrayList<G10Plan>> plans) {
		
		
		ArrayList<G10Plan> outputPlans = new ArrayList<G10Plan>();
		
		for (ArrayList<G10Plan> planList : plans.values()) {
			
			G10Plan minPlan = planList.get(0);
			long minCost = minPlan.getPlan().getCumulativeCosts();
			
			for (int i = 1; i < planList.size(); i++) {
				G10Plan plan = planList.get(i);
				

					if (plan.getPlan().getCumulativeCosts() < minCost) {
						
						
						minCost = plan.getPlan().getCumulativeCosts();
						minPlan = plan;
					}
				
			}
			outputPlans.add(minPlan);
		}
		
		return  (G10Plan[]) outputPlans.toArray(new G10Plan[outputPlans.size()]);		
	}
	

	/*
	 * Helper class to store useful information for the different plans
	 */
	private class G10Plan {
		
		private OptimizerPlanOperator plan;
		private int relationBitmap;
		private int neighbourBitmap;
		
		public G10Plan(OptimizerPlanOperator plan, int relationBitmap, int neighbourBitmap) {
			this.plan = plan;
			this.relationBitmap = relationBitmap;
			this.neighbourBitmap = neighbourBitmap;		
		}
		
		public int getNeighbourBitmap() {
			return this.neighbourBitmap;
		}
		
		public int getRelationBitmap() {
			return this.relationBitmap;
		}
		
		public OptimizerPlanOperator getPlan() {
			return plan;
		}	
	}
}
