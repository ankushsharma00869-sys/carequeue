import React from 'react';

interface BadgeProps {
  children: React.ReactNode;
  variant?: 'normal' | 'priority' | 'emergency' | 'success' | 'warning' | 'neutral';
  size?: 'sm' | 'md' | 'lg';
}

export const Badge: React.FC<BadgeProps> = ({ children, variant = 'neutral', size = 'sm' }) => {
  const styles = {
    normal: 'bg-accent/15 text-accent border-accent/30',
    priority: 'bg-warning/15 text-warning border-warning/30',
    emergency: 'bg-emergency/20 text-emergency border-emergency/40 font-semibold',
    success: 'bg-success/15 text-success border-success/30',
    warning: 'bg-warning/15 text-warning border-warning/30',
    neutral: 'bg-surface-elevated text-secondary-text border-border',
  };

  const sizes = {
    sm: 'px-2 py-0.5 text-xs',
    md: 'px-2.5 py-1 text-xs font-medium',
    lg: 'px-3 py-1.5 text-sm font-semibold tracking-wide',
  };

  return (
    <span className={`inline-flex items-center rounded border ${styles[variant]} ${sizes[size]}`}>
      {children}
    </span>
  );
};