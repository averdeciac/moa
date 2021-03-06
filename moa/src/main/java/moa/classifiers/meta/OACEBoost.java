/*
 *    OACEBoost.java
 *
 *    @author Alberto Verdecia-Cabrera
 * 
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *    
 *    
 */
package moa.classifiers.meta;

import com.github.javacliparser.FlagOption;
import com.github.javacliparser.IntOption;
import com.yahoo.labs.samoa.instances.Instance;
import moa.classifiers.AbstractClassifier;
import moa.classifiers.Classifier;
import moa.classifiers.MultiClassClassifier;
import moa.classifiers.core.driftdetection.ChangeDetector;
import moa.core.DoubleVector;
import moa.core.Measurement;
import moa.core.MiscUtils;
import moa.options.ClassOption;


/**
 *
 * @author Alberto Verdecia Cabrera (averdeciac at gmail dot com)
 * <p>See details in:<br /> Verdecia-Cabrera, Alberto, Isvani Frias Blanco, and André CPLF Carvalho. 
 * "An online adaptive classifier ensemble for mining non-stationary data streams." 
 *  Intelligent Data Analysis 22.4 (2018): 787-806.</p>
 */
public class OACEBoost extends AbstractClassifier implements MultiClassClassifier{
    
    public ClassOption baseLearnerOption = new ClassOption("baseLearner", 'b',
            "Classifier to train.", Classifier.class, "drift.AdaptiveClassifier");

    public IntOption ensembleSizeOption = new IntOption("ensembleSize", 's',
            "The number of models in the bag.", 10, 1, Integer.MAX_VALUE);
    public ClassOption driftDetectionMethodOption = new ClassOption("driftDetectionMethod", 'd',
            "Drift detection method to use.", ChangeDetector.class, "HDDM_A_Test");
    public FlagOption deleteOption = new FlagOption("deleWorstCalssifier", 'i',
            "Delete the worst classifier.");

    protected Classifier[] ensemble;

    protected ChangeDetector[] estimator;

    protected Classifier[] alternativeClassifier;

    protected ChangeDetector[] alternativeDetector;
    protected double[] scms;

    protected double[] swms;
    
    @Override
     public void resetLearningImpl() {

         this.ensemble = new Classifier[this.ensembleSizeOption.getValue()];
        this.alternativeClassifier = new Classifier[this.ensembleSizeOption.getValue()];
        this.alternativeDetector = new ChangeDetector[this.ensembleSizeOption.getValue()];
        this.estimator = new ChangeDetector[this.ensemble.length];

        Classifier baseLearner = (Classifier) getPreparedClassOption(this.baseLearnerOption);
        baseLearner.resetLearning();
        for (int i = 0; i < this.ensemble.length; i++) {
            this.ensemble[i] = baseLearner.copy();
            this.alternativeClassifier[i] = null;
            this.estimator[i] = ((ChangeDetector) getPreparedClassOption(this.driftDetectionMethodOption)).copy();;
            this.alternativeDetector[i] = ((ChangeDetector) getPreparedClassOption(this.driftDetectionMethodOption)).copy();
        }
        this.scms = new double[this.ensemble.length];
        this.swms = new double[this.ensemble.length];

    }
      @Override
    public void trainOnInstanceImpl(Instance inst) {
        
        double lambda_d = 1.0;
        boolean change = false;
        for (int i = 0; i < this.ensemble.length; i++) {
            int k = MiscUtils.poisson(lambda_d, this.classifierRandom);
            if (k > 0) {
                Instance weightedInst = (Instance) inst.copy();
                weightedInst.setWeight(inst.weight() * k);
                this.ensemble[i].trainOnInstance(weightedInst);
            }
            boolean correctlyClassifies = this.ensemble[i].correctlyClassifies(inst);
            if (correctlyClassifies) {
                this.scms[i] += lambda_d;
                lambda_d *= this.trainingWeightSeenByModel / (2 * this.scms[i]);
            } else {
                this.swms[i] += lambda_d;
                lambda_d *= this.trainingWeightSeenByModel / (2 * this.swms[i]);
            }
            this.estimator[i].input(correctlyClassifies ? 0 : 1);
            if (this.deleteOption.isSet()) {
                if (this.estimator[i].getChange() == true) {
                    change = true;
                }
            } else{
              if (this.estimator[i].getWarningZone() == true) {
                if (this.alternativeClassifier[i] == null) {
                    this.alternativeClassifier[i] = ((Classifier) getPreparedClassOption(this.baseLearnerOption)).copy();
                    this.alternativeClassifier[i].trainOnInstance(inst);
                    boolean correctlyClassifies1 = this.alternativeClassifier[i].correctlyClassifies(inst);
                    this.alternativeDetector[i].input(correctlyClassifies1 ? 0 : 1);
                }

            } else if (this.estimator[i].getChange() == true) {
                if (this.alternativeClassifier[i] != null) {
                    if (this.estimator[i].getEstimation() < this.alternativeDetector[i].getEstimation()) {
                        this.ensemble[i] = this.alternativeClassifier[i].copy();
                        this.estimator[i] = (ChangeDetector) this.alternativeDetector[i].copy();
                    }
                    this.alternativeClassifier[i] = null;
                } else {
                    this.ensemble[i].resetLearning(); /*= ((Classifier) getPreparedClassOption(this.baseLearnerOption)).copy();*/

                    this.estimator[i] = (ChangeDetector) this.alternativeDetector[i].copy();
                }

            } else {
                this.alternativeClassifier[i] = null;
            }
        }
        }
        if (change) {
            double max = 0.0;
            int imax = -1;
            for (int i = 0; i < this.ensemble.length; i++) {
                if (max < this.estimator[i].getEstimation()) {
                    max = this.estimator[i].getEstimation();
                    imax = i;
                }
            }
            if (imax != -1) {
                this.ensemble[imax].resetLearning();
                this.estimator[imax] = ((ChangeDetector) getPreparedClassOption(this.driftDetectionMethodOption)).copy();
            }

        }

    }
      @Override
    protected Measurement[] getModelMeasurementsImpl() {
        return new Measurement[]{new Measurement("ensemble size",
            this.ensemble != null ? this.ensemble.length : 0)};
    }

    @Override
    public void getModelDescription(StringBuilder out, int indent) {
        // TODO Auto-generated method stub
    }

    protected double getEnsembleMemberWeight(int i) {
        double em = this.swms[i] / (this.scms[i] + this.swms[i]);
        if ((em == 0.0) || (em > 0.5)) {
            return 0.0;
        }
        double Bm = em / (1.0 - em);
        return Math.log(1.0 / Bm);

    }

    @Override
    public double[] getVotesForInstance(Instance inst) {
        DoubleVector combinedVote = new DoubleVector();
        for (int i = 0; i < this.ensemble.length; i++) {
            double memberWeight = getEnsembleMemberWeight(i);
            if (memberWeight > 0.0) {
                DoubleVector vote = new DoubleVector(this.ensemble[i].getVotesForInstance(inst));
                if (vote.sumOfValues() > 0.0) {
                    vote.normalize();
                    vote.scaleValues(memberWeight);
                    combinedVote.addValues(vote);
                }
            } else {
                break;
            }
        }
        return combinedVote.getArrayRef();
    }

    @Override
    public boolean isRandomizable() {
        return true;
    }
}
