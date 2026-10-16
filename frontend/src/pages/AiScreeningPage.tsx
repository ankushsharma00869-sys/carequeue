import React, { useState } from 'react';
import api from '../api/client';
import { SymptomScreeningResponse } from '../types';
import { FileSearch, AlertCircle, CheckCircle, ArrowRight, ShieldAlert, Stethoscope } from 'lucide-react';
import { Badge } from '../components/common/Badge';

export const AiScreeningPage: React.FC = () => {
  const [symptoms, setSymptoms] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<SymptomScreeningResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleAnalyze = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!symptoms.trim()) return;

    setLoading(true);
    setError(null);
    try {
      const res = await api.post<SymptomScreeningResponse>('/ai/symptom-screening', {
        symptoms,
      });
      setResult(res.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to complete clinical symptom analysis.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="p-8 max-w-4xl mx-auto space-y-8">
      {/* Header */}
      <div>
        <h1 className="text-xl font-bold text-primary-text tracking-wide">
          Clinical Symptom Pre-Screening
        </h1>
        <p className="text-xs text-secondary-text mt-1">
          Assists preliminary clinical triage by mapping patient-reported symptoms to relevant hospital departments.
        </p>
      </div>

      {/* COMPACT CLINICAL FORM (Section 20 requirement: no giant chatbots, no neon AI blobs) */}
      <div className="bg-surface border border-border rounded-lg p-6 shadow-xl">
        <form onSubmit={handleAnalyze} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-primary-text mb-1">
              Describe Symptoms & Chief Complaints
            </label>
            <textarea
              rows={4}
              required
              value={symptoms}
              onChange={(e) => setSymptoms(e.target.value)}
              placeholder="Apne lakshan likhein, jaise: bukhar hai, khansi bhi hai"
              className="w-full p-3 bg-surface-elevated border border-border rounded text-xs text-primary-text placeholder-muted-text focus:outline-none focus:border-accent"
            />
          </div>
          {error && (
            <div className="p-3 rounded bg-emergency/15 border border-emergency/30 text-xs text-emergency">
              {error}
            </div>
          )}

          <div className="flex justify-end pt-2">
            <button
              type="submit"
              disabled={loading || !symptoms.trim()}
              className="px-5 py-2 rounded bg-accent hover:bg-accent-hover text-white text-xs font-semibold tracking-wide transition-colors flex items-center space-x-2 disabled:opacity-50"
            >
              <FileSearch className="w-3.5 h-3.5" />
              <span>{loading ? 'Analyzing Clinical Presentation...' : 'Analyze Symptoms'}</span>
            </button>
          </div>
        </form>
      </div>

      {/* CLINICAL RESULT CARD */}
      {result && (
        <div className="bg-surface border border-border rounded-lg p-6 shadow-xl space-y-6 animate-in fade-in duration-200">
          <div className="flex items-center justify-between pb-4 border-b border-border">
            <div className="flex items-center space-x-2">
              <Stethoscope className="w-4 h-4 text-accent" />
              <h2 className="text-sm font-semibold text-primary-text">Pre-Screening Clinical Analysis</h2>
            </div>
            <Badge
              variant={
                result.urgencyLevel === 'Emergency'
                  ? 'emergency'
                  : result.urgencyLevel === 'Priority'
                  ? 'warning'
                  : 'normal'
              }
              size="md"
            >
              Urgency: {result.urgencyLevel}
            </Badge>
          </div>

          <div className="grid md:grid-cols-2 gap-4">
            <div className="p-4 rounded bg-surface-elevated border border-border space-y-2">
              <div className="text-[11px] font-semibold text-muted-text uppercase tracking-wider">
                SUGGESTED DEPARTMENT
              </div>
              <div className="text-lg font-bold text-accent">
                {result.suggestedDepartment}
              </div>
              <p className="text-xs text-secondary-text">
                {result.clinicalReasoning}
              </p>
            </div>

            <div className="p-4 rounded bg-surface-elevated border border-border space-y-2">
              <div className="text-[11px] font-semibold text-muted-text uppercase tracking-wider">
                SYMPTOM SUMMARY
              </div>
              <p className="text-xs text-primary-text italic">
                {result.symptomSummary}
              </p>
            </div>
          </div>

          {/* QUESTIONS FOR DOCTOR (Section 19 requirement) */}
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-secondary-text mb-3">
              Questions to Discuss With Your Physician
            </h3>
            <ul className="space-y-2 text-xs">
              {result.questionsForDoctor.map((q, idx) => (
                <li key={idx} className="flex items-start space-x-2 p-2.5 rounded bg-surface-elevated/50 border border-border">
                  <span className="text-accent font-bold">•</span>
                  <span className="text-primary-text">{q}</span>
                </li>
              ))}
            </ul>
          </div>

          {/* MANDATORY MEDICAL DISCLAIMER (Section 19 requirement) */}
          <div className="p-4 rounded bg-amber-500/10 border border-amber-500/30 flex items-start space-x-3 text-xs text-amber-300">
            <ShieldAlert className="w-4 h-4 shrink-0 mt-0.5 text-amber-400" />
            <div>
              <strong>Clinical Guardrail Notice:</strong> {result.medicalDisclaimer}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};