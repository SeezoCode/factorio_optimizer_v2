data:extend({
  { type = "bool-setting", name = "factory-flow-enabled", setting_type = "runtime-global", default_value = true, order = "a" },
  { type = "bool-setting", name = "factory-flow-pull", setting_type = "runtime-global", default_value = true, order = "b" },
  { type = "double-setting", name = "factory-flow-pull-ahead", setting_type = "runtime-global", default_value = 1, minimum_value = 1, maximum_value = 10, order = "b2" },
  { type = "double-setting", name = "factory-flow-stock", setting_type = "runtime-global", default_value = 3, minimum_value = 0, maximum_value = 10, order = "b3" },
  { type = "double-setting", name = "factory-flow-side-cap", setting_type = "runtime-global", default_value = 0, minimum_value = 0, maximum_value = 100, order = "b4" },
  { type = "bool-setting", name = "factory-flow-requester-demand", setting_type = "runtime-global", default_value = true, order = "b5" },
  { type = "bool-setting", name = "factory-flow-quotas", setting_type = "runtime-global", default_value = true, order = "c" },
  { type = "double-setting", name = "factory-flow-quota-factor", setting_type = "runtime-global", default_value = 1.5, minimum_value = 1, maximum_value = 100, order = "d" },
  { type = "int-setting", name = "factory-flow-quota-window", setting_type = "runtime-global", default_value = 0, minimum_value = 0, maximum_value = 600, order = "d2" },
  { type = "bool-setting", name = "factory-flow-gates", setting_type = "runtime-global", default_value = true, order = "e" },
})
