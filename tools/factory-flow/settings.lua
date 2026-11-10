data:extend({
  { type = "bool-setting", name = "factory-flow-enabled", setting_type = "runtime-global", default_value = true, order = "a" },
  { type = "bool-setting", name = "factory-flow-pull", setting_type = "runtime-global", default_value = true, order = "b" },
  { type = "bool-setting", name = "factory-flow-quotas", setting_type = "runtime-global", default_value = true, order = "c" },
  { type = "double-setting", name = "factory-flow-quota-factor", setting_type = "runtime-global", default_value = 1.5, minimum_value = 1, maximum_value = 100, order = "d" },
  { type = "bool-setting", name = "factory-flow-gates", setting_type = "runtime-global", default_value = true, order = "e" },
})
