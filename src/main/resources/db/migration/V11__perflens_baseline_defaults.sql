-- Older PerfLens builds omit the diagnostic fields; default values preserve their findings.
-- Newer builds explicitly provide the evidence/confidence and override these defaults.
alter table perflens_finding
    alter column confidence set default 'LOW',
    alter column recommendation_text set default '';
