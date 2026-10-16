-- TechEmpower plaintext style: pipeline N requests per write
init = function(args)
  local r = {}
  local depth = tonumber(args[1]) or 16
  for i = 1, depth do
    r[i] = wrk.format(nil)
  end
  req = table.concat(r)
end

request = function()
  return req
end
