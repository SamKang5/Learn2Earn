import { runLearningPlanRefills, LearningPlanWorkerEnv } from "./learning-plan-worker";

export default {
  async scheduled(_controller, env, _ctx) {
    const result = await runLearningPlanRefills(env);
    console.log(JSON.stringify({ event: "learning_plan_refill_run", ...result }));
  },
} satisfies ExportedHandler<LearningPlanWorkerEnv>;
