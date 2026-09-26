select
  /*%expand*/*
from
  orders
where
  order_date between /* from */'2026-01-01' and /* to */'2026-12-31'
order by
  id
