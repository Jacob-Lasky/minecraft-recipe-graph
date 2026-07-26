--[[ ae2_dump.lua -- live AE2 network export for OpenComputers (MC 1.12.2)

Writes the ME network's contents to a JSON file that `mbcgraph plan --have` reads
directly, in the same shape as the offline world-save reader produces.

Why bother when the offline reader exists: this sees what the NETWORK sees, not just
what is sitting in drives. Storage buses, ME interfaces and external inventories are
all included, and `getCraftables()` additionally reports what AE2 can already
autocraft -- which the planner treats as a stopping condition, so the tree does not
expand branches your ME system would just make for you.

SETUP
  1. Any OC computer (Tier 1 is enough) with a screen, keyboard and disk.
  2. Place an Adapter block touching an ME Controller or ME Interface on the network.
  3. Copy this file onto the computer, then:  ae2_dump
  4. The file lands in the world save under
       <world>/opencomputers/<fs-address>/ae2_have.json
     Pull it out with:  docker exec AMP cat .../opencomputers/<addr>/ae2_have.json

The Adapter is what exposes the network as a component. Without it the proxy lookup
below finds nothing -- a computer merely placed next to a controller is not connected.
]]

local component = require("component")
local fs = require("filesystem")

local OUT = "/home/ae2_have.json"

-- Accept a controller or an interface: both carry the NetworkControl methods.
local function findNetwork()
  for _, name in ipairs({ "me_controller", "me_interface" }) do
    if component.isAvailable(name) then
      return component.getPrimary(name), name
    end
  end
  return nil, nil
end

-- Minimal JSON string escaping. Item labels contain quotes and section signs, and a
-- single unescaped quote makes the whole dump unparseable on the python side.
local function esc(s)
  s = tostring(s or "")
  s = s:gsub("\\", "\\\\"):gsub('"', '\\"')
  s = s:gsub("\n", "\\n"):gsub("\r", "\\r"):gsub("\t", "\\t")
  -- strip Minecraft formatting codes so labels match items.csv
  s = s:gsub("\194\167.", "")
  return s
end

-- Canonical key must match mbcgraph.model.norm_key: meta 0 is omitted entirely.
local function key(name, damage)
  damage = tonumber(damage) or 0
  if damage == 0 then
    return name
  end
  return name .. ":" .. tostring(damage)
end

local function main()
  local net, kind = findNetwork()
  if not net then
    io.stderr:write("no me_controller/me_interface component found.\n")
    io.stderr:write("place an Adapter block touching your ME Controller.\n")
    return 1
  end
  print("using " .. kind)

  local ok, items = pcall(net.getItemsInNetwork)
  if not ok or type(items) ~= "table" then
    io.stderr:write("getItemsInNetwork failed: " .. tostring(items) .. "\n")
    return 1
  end

  -- Aggregate: the same item can appear in several entries across cells.
  local counts, labels, n = {}, {}, 0
  for _, it in ipairs(items) do
    if it.name then
      local k = key(it.name, it.damage)
      local size = tonumber(it.size) or 0
      if size > 0 then
        if not counts[k] then
          counts[k] = 0
          n = n + 1
        end
        counts[k] = counts[k] + size
        labels[k] = labels[k] or it.label
      end
    end
  end

  local fluids = {}
  if net.getFluidsInNetwork then
    local fok, fl = pcall(net.getFluidsInNetwork)
    if fok and type(fl) == "table" then
      for _, f in ipairs(fl) do
        local nm = f.name or (f.label and f.label:lower())
        if nm and (tonumber(f.amount) or 0) > 0 then
          fluids[nm] = (fluids[nm] or 0) + f.amount
        end
      end
    end
  end

  -- Craftables become a stopping condition in the planner, not a quantity.
  local craftables, cn = {}, 0
  if net.getCraftables then
    local cok, cr = pcall(net.getCraftables)
    if cok and type(cr) == "table" then
      for _, c in ipairs(cr) do
        local st = c.getItemStack and select(2, pcall(c.getItemStack)) or nil
        if type(st) == "table" and st.name then
          craftables[key(st.name, st.damage)] = true
          cn = cn + 1
        end
      end
    end
  end

  if fs.exists(OUT) then
    fs.remove(OUT)
  end
  local fh, err = io.open(OUT, "w")
  if not fh then
    io.stderr:write("cannot write " .. OUT .. ": " .. tostring(err) .. "\n")
    return 1
  end

  fh:write('{\n "source": "opencomputers",\n')
  fh:write(' "stats": {"items": ' .. n .. ', "craftables": ' .. cn .. '},\n')

  local function writeMap(name, tbl, valueFn, last)
    fh:write(' "' .. name .. '": {')
    local first = true
    for k, v in pairs(tbl) do
      if not first then fh:write(",") end
      first = false
      fh:write('\n  "' .. esc(k) .. '": ' .. valueFn(v))
    end
    fh:write(first and "}" or "\n }")
    fh:write(last and "\n" or ",\n")
  end

  writeMap("items", counts, function(v) return string.format("%d", v) end)
  writeMap("fluids", fluids, function(v) return string.format("%d", v) end)
  writeMap("names", labels, function(v) return '"' .. esc(v) .. '"' end)
  writeMap("craftables", craftables, function() return "true" end, true)
  fh:write("}\n")
  fh:close()

  print(string.format("wrote %s: %d items, %d craftables", OUT, n, cn))
  return 0
end

os.exit(main())
