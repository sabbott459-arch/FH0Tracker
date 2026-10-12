# FH0Tracker Supabase setup (stage 1)

The public client configuration is in `../supabase-config.js`.

1. In your FH0Tracker Supabase project, open **SQL Editor**.
2. Create a new query and paste `setup.sql`.
3. Run it once. It creates the tables with row-level security enabled and no public data policies.

**This stage intentionally does not enable campaign login or cloud saves.** The next stage needs server-side PIN verification, session tokens, scoped character updates, and snapshot recovery. Do not add permissive RLS policies or send a service-role key to the browser.

Existing FH0Tracker character data continues to save locally until the cloud workflow is ready.
