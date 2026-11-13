package com.cexpilot.platform.plan;

/** 首版失败即停，保留已完成结果；不自动回滚外部业务操作。 */
public enum PlanFailurePolicy { STOP_ON_FAILURE }
