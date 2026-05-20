AI_ADVISORY_ONLY_NOTICE = (
    "AI worker output is advisory only. It must not create or mutate orders, quotes, "
    "inventory, prices, customers, product master data, ERP data, or trusted business tables."
)


FORBIDDEN_BUSINESS_MUTATIONS = frozenset(
    {
        "create_order",
        "approve_quote",
        "update_inventory",
        "change_price",
        "update_customer",
        "write_erp",
    }
)


def assert_advisory_task(task_name: str) -> None:
    if task_name in FORBIDDEN_BUSINESS_MUTATIONS:
        raise ValueError(f"AI worker cannot execute business mutation task: {task_name}")