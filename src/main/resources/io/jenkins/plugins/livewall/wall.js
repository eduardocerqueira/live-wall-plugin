/*
 * Live Wall - the browser half of the plugin.
 *
 * The server sends a small JSON document; everything else happens here, because a wall that is
 * going to sit on a television for six months must not reload the page and must not care that the
 * screen is 1366x768 in one office and 3840x2160 in another.
 *
 * The three things worth reading:
 *
 *   fitColumns()   picks the column count that makes every tile as large as possible while still
 *                  fitting all of them on screen. This is what replaces pagination.
 *   fitFontSize()  measures the real glyphs on a canvas and binary-searches the largest font size
 *                  at which a job name still fits its tile, instead of guessing and clipping.
 *   inkFor()       picks black or white text per status from the actual contrast ratio, so a
 *                  hand-picked custom palette stays readable without anyone thinking about it.
 */
(function () {
    "use strict";

    var STATUSES = ["success", "failure", "unstable", "aborted", "notbuilt", "disabled"];

    /* Custom colour overrides, and the CSS custom properties each one drives. */
    var COLOR_TARGETS = {
        background: ["--lw-bg"],
        success: ["--lw-success"],
        failure: ["--lw-failure"],
        unstable: ["--lw-unstable"],
        aborted: ["--lw-aborted"],
        idle: ["--lw-notbuilt", "--lw-disabled"],
    };

    /* Fraction of a tile that the silhouette takes away from the label on each side.
       Must stay in step with --lw-text-inset in live-wall.css. */
    var SHAPE_INSET = {
        rectangle: 0.07,
        rounded: 0.08,
        square: 0.07,
        circle: 0.18,
        octagon: 0.13,
        hexagon: 0.16,
        diamond: 0.26,
        parallelogram: 0.14,
        chevron: 0.14,
        cross: 0.3,
    };

    /* Tile proportions each shape looks best at. Only an input to the column search: tiles still
       stretch to fill their grid cell, except for the shapes forced square below. */
    var SHAPE_ASPECT = {
        rectangle: 2.2,
        rounded: 2.2,
        square: 1,
        circle: 1,
        octagon: 1.3,
        hexagon: 1.4,
        diamond: 1,
        parallelogram: 2.2,
        chevron: 2,
        cross: 1,
    };

    var SQUARE_SHAPES = { square: 1, circle: 1, diamond: 1, cross: 1 };

    /* Overrides accepted on the URL, so a wall can be retuned from the TV's address bar without
       touching the saved view configuration. */
    var URL_OVERRIDES = {
        palette: "palette",
        shape: "shape",
        animation: "animation",
        sizing: "sizing",
        header: "header",
        burnin: "burnIn",
    };

    /** Map lookup that cannot be steered off the prototype chain by a crafted URL parameter. */
    function lookup(map, key, fallback) {
        return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : fallback;
    }

    /* Canvas gives exact glyph widths, but it is not guaranteed: some kiosk browsers and privacy
       settings disable it outright. Everything that uses it degrades to an estimate instead. */
    var measureContext = (function () {
        try {
            return document.createElement("canvas").getContext("2d");
        } catch (error) {
            return null;
        }
    })();
    var glyphWidths = Object.create(null);

    /* Average glyph width as a fraction of the font size, used when canvas is unavailable. */
    var ESTIMATED_GLYPH_RATIO = 0.55;

    function boot() {
        var roots = document.querySelectorAll(".lw-root");
        for (var i = 0; i < roots.length; i++) {
            new Wall(roots[i]).start();
        }
    }

    // Adjuncts can land either side of DOMContentLoaded depending on where Jenkins hoists them.
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot);
    } else {
        boot();
    }

    function Wall(root) {
        this.root = root;
        this.grid = root.querySelector(".lw-grid");
        this.scroller = root.querySelector(".lw-scroller");
        this.stage = root.querySelector(".lw-stage");
        this.banner = root.querySelector(".lw-banner");
        this.emptyMessage = root.querySelector(".lw-message");
        this.clock = root.querySelector(".lw-clock");

        this.counts = {};
        var self = this;
        ["failure", "unstable", "building", "success"].forEach(function (key) {
            self.counts[key] = root.querySelector('.lw-count[data-status="' + key + '"]');
        });

        this.dataUrl = root.dataset.dataUrl;
        this.rootUrl = root.dataset.rootUrl || "";
        this.refreshMs = Math.max(2000, (parseInt(root.dataset.refresh, 10) || 6) * 1000);
        this.minTileHeight = parseInt(root.dataset.minTileHeight, 10) || 110;
        this.showBuildNumber = root.dataset.showBuildNumber === "true";

        this.tiles = new Map(); // full job name -> element
        this.building = []; // entries with a build in progress, for the progress ticker
        this.order = "";
        this.failures = 0;
        this.clockSkew = 0; // server time minus browser time, so a wrong TV clock cannot lie
        this.scrollAnimation = null;
        this.pendingLayout = 0;
        this.timer = 0;
    }

    Wall.prototype.start = function () {
        this.applyUrlOverrides();
        this.applyCustomColors();
        this.refreshInk();
        this.bindControls();

        var self = this;
        if (window.ResizeObserver) {
            new ResizeObserver(function () {
                self.scheduleLayout();
            }).observe(this.stage);
        } else {
            window.addEventListener("resize", function () {
                self.scheduleLayout();
            });
        }
        document.addEventListener("fullscreenchange", function () {
            self.scheduleLayout();
        });
        document.addEventListener("visibilitychange", function () {
            if (!document.hidden) {
                self.poll();
            }
        });

        this.tickClock();
        window.setInterval(this.tickClock.bind(this), 10000);
        window.setInterval(this.tickProgress.bind(this), 500);
        this.poll();
    };

    /* ------------------------------------------------------------------ configuration */

    Wall.prototype.applyUrlOverrides = function () {
        if (!window.URLSearchParams) {
            return;
        }
        var params = new URLSearchParams(window.location.search);
        var root = this.root;
        Object.keys(URL_OVERRIDES).forEach(function (param) {
            var value = params.get(param);
            if (value === null) {
                return;
            }
            var key = URL_OVERRIDES[param];
            var enabled = value === "1" || value === "true";
            if (key === "header") {
                root.dataset.showHeader = enabled ? "true" : "false";
            } else if (key === "burnIn") {
                root.dataset.burnIn = enabled ? "true" : "false";
            } else {
                root.dataset[key] = value;
            }
        });

        var refresh = parseInt(params.get("refresh"), 10);
        if (refresh > 0) {
            this.refreshMs = Math.max(2000, refresh * 1000);
        }
        var header = this.root.querySelector(".lw-header");
        if (header) {
            header.dataset.visible = this.root.dataset.showHeader === "false" ? "false" : "true";
        }
    };

    /* Custom colours override whichever palette is selected, so you can start from a built-in one
       and change only the colour that bothers you. */
    Wall.prototype.applyCustomColors = function () {
        var root = this.root;
        Object.keys(COLOR_TARGETS).forEach(function (key) {
            var value = root.dataset["color" + key.charAt(0).toUpperCase() + key.slice(1)];
            if (!value) {
                return;
            }
            COLOR_TARGETS[key].forEach(function (property) {
                root.style.setProperty(property, value);
            });
        });
    };

    /* Picks the text colour for each status from its measured contrast against the fill. */
    Wall.prototype.refreshInk = function () {
        var computed = window.getComputedStyle(this.root);
        for (var i = 0; i < STATUSES.length; i++) {
            var status = STATUSES[i];
            var fill = computed.getPropertyValue("--lw-" + status).trim();
            if (!fill) {
                continue; // stylesheet not applied yet; the CSS fallback ink is fine
            }
            this.root.style.setProperty("--lw-ink-" + status, inkFor(fill));
        }
    };

    Wall.prototype.bindControls = function () {
        var self = this;

        var fullscreen = document.querySelector('[data-lw-action="fullscreen"]');
        if (fullscreen) {
            fullscreen.addEventListener("click", function (event) {
                event.preventDefault();
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                } else if (self.root.requestFullscreen) {
                    self.root.requestFullscreen();
                }
            });
        }

        var selects = document.querySelectorAll("[data-lw-preview]");
        for (var i = 0; i < selects.length; i++) {
            var current = this.root.dataset[selects[i].getAttribute("data-lw-preview")];
            if (current) {
                selects[i].value = current;
            }
            selects[i].addEventListener("change", function (event) {
                var key = event.target.getAttribute("data-lw-preview");
                self.root.dataset[key] = event.target.value;
                self.refreshInk();
                self.scheduleLayout();
            });
        }
    };

    /* ------------------------------------------------------------------ polling */

    Wall.prototype.poll = function () {
        var self = this;
        window.clearTimeout(this.timer);

        if (document.hidden) {
            // Nobody is looking; do not spend controller time on this tab.
            this.timer = window.setTimeout(this.poll.bind(this), this.refreshMs * 4);
            return;
        }

        fetch(this.dataUrl, {
            credentials: "same-origin",
            cache: "no-store",
            headers: { Accept: "application/json" },
        })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error("HTTP " + response.status);
                }
                return response.json();
            })
            .then(function (data) {
                self.failures = 0;
                self.banner.classList.remove("lw-on");
                if (typeof data.generatedAt === "number") {
                    self.clockSkew = data.generatedAt - Date.now();
                }
                self.render(data.tiles || []);
            })
            .catch(function (error) {
                self.failures++;
                // One dropped poll on a wifi TV is not news; three in a row is.
                if (self.failures >= 3) {
                    self.banner.textContent = "Lost contact with Jenkins - " + error.message;
                    self.banner.classList.add("lw-on");
                }
            })
            .then(function () {
                self.timer = window.setTimeout(self.poll.bind(self), self.refreshMs);
            });
    };

    /* ------------------------------------------------------------------ rendering */

    Wall.prototype.render = function (list) {
        var self = this;
        var structureChanged = false;
        var seen = new Set();

        list.forEach(function (tile) {
            seen.add(tile.name);
            var element = self.tiles.get(tile.name);
            if (!element) {
                element = self.createTile(tile);
                self.tiles.set(tile.name, element);
                self.grid.appendChild(element);
                structureChanged = true;
            }
            if (self.updateTile(element, tile)) {
                structureChanged = true;
            }
        });

        this.tiles.forEach(function (element, name) {
            if (!seen.has(name)) {
                element.remove();
                self.tiles.delete(name);
                structureChanged = true;
            }
        });

        var order = list
            .map(function (tile) {
                return tile.name;
            })
            .join("\n");
        if (order !== this.order) {
            list.forEach(function (tile) {
                self.grid.appendChild(self.tiles.get(tile.name));
            });
            this.order = order;
            structureChanged = true;
        }

        this.building = list
            .filter(function (tile) {
                return tile.building;
            })
            .map(function (tile) {
                return { tile: tile, element: self.tiles.get(tile.name) };
            });

        this.emptyMessage.textContent = this.root.dataset.emptyMessage || "Nothing to show.";
        this.emptyMessage.classList.toggle("lw-on", list.length === 0);
        this.updateCounts(list);
        this.tickProgress();

        if (structureChanged) {
            this.scheduleLayout();
        }
    };

    Wall.prototype.createTile = function (data) {
        var anchor = document.createElement("a");
        anchor.className = "lw-tile";
        anchor.href = this.rootUrl + data.url;
        anchor.setAttribute("role", "listitem");

        var shape = document.createElement("span");
        shape.className = "lw-shape";

        var effects = document.createElement("span");
        effects.className = "lw-fx";
        effects.setAttribute("aria-hidden", "true");

        var label = document.createElement("span");
        label.className = "lw-label";

        shape.appendChild(effects);
        shape.appendChild(label);

        if (this.showBuildNumber) {
            var badge = document.createElement("span");
            badge.className = "lw-badge";
            badge.setAttribute("aria-hidden", "true");
            shape.appendChild(badge);
        }

        anchor.appendChild(shape);
        return anchor;
    };

    /** Returns true when something changed that needs the layout recomputing. */
    Wall.prototype.updateTile = function (element, data) {
        var needsLayout = false;

        if (element.dataset.label !== data.label) {
            element.dataset.label = data.label;
            setLabel(element.querySelector(".lw-label"), data.label);
            needsLayout = true;
        }
        if (element.dataset.status !== data.status) {
            element.dataset.status = data.status;
        }
        setFlag(element, "building", data.building);
        setFlag(element, "queued", data.queued);

        var description = data.name + ": " + data.status + (data.building ? ", building" : "");
        if (element.getAttribute("aria-label") !== description) {
            element.setAttribute("aria-label", description);
            element.title = description;
        }

        if (this.showBuildNumber) {
            var badge = element.querySelector(".lw-badge");
            var text = data.buildNumber ? "#" + data.buildNumber : "";
            if (badge && badge.textContent !== text) {
                badge.textContent = text;
            }
        }
        return needsLayout;
    };

    Wall.prototype.updateCounts = function (list) {
        var totals = { failure: 0, unstable: 0, building: 0, success: 0 };
        list.forEach(function (tile) {
            if (totals[tile.status] !== undefined) {
                totals[tile.status]++;
            }
            if (tile.building) {
                totals.building++;
            }
        });

        var labels = {
            failure: "failing",
            unstable: "unstable",
            building: "building",
            success: "passing",
        };
        var self = this;
        Object.keys(totals).forEach(function (key) {
            var element = self.counts[key];
            if (!element) {
                return;
            }
            element.textContent = totals[key] + " " + labels[key];
            // A zero for something bad is worth saying out loud; a zero for the rest is noise.
            element.classList.toggle("lw-on", totals[key] > 0 || key === "failure");
        });
    };

    /* Advances the progress fills between polls, so a five second refresh still looks continuous. */
    Wall.prototype.tickProgress = function () {
        var now = Date.now() + this.clockSkew;
        for (var i = 0; i < this.building.length; i++) {
            var element = this.building[i].element;
            if (!element) {
                continue;
            }
            var data = this.building[i].tile;
            var effects = element.querySelector(".lw-fx");
            var estimate = data.estimatedDuration;
            var elapsed = now - data.startedAt;

            if (!estimate || estimate <= 0 || elapsed >= estimate) {
                // No estimate, or the build has already overrun it: stop pretending we know.
                element.dataset.indeterminate = "true";
                effects.style.width = "";
            } else {
                delete element.dataset.indeterminate;
                var fraction = Math.max(0.02, Math.min(0.99, elapsed / estimate));
                effects.style.width = (fraction * 100).toFixed(1) + "%";
            }
        }
    };

    Wall.prototype.tickClock = function () {
        if (!this.clock) {
            return;
        }
        this.clock.textContent = new Date().toLocaleTimeString([], {
            hour: "2-digit",
            minute: "2-digit",
        });
    };

    /* ------------------------------------------------------------------ layout */

    Wall.prototype.scheduleLayout = function () {
        var self = this;
        if (this.pendingLayout) {
            return;
        }
        this.pendingLayout = window.requestAnimationFrame(function () {
            self.pendingLayout = 0;
            self.layout();
        });
    };

    Wall.prototype.layout = function () {
        var count = this.tiles.size;
        if (!count) {
            return;
        }
        var width = this.scroller.clientWidth;
        var height = this.scroller.clientHeight;
        if (width < 10 || height < 10) {
            return;
        }

        var shape = this.root.dataset.shape || "rounded";
        var aspect = lookup(SHAPE_ASPECT, shape, 2);
        var gap = parseFloat(window.getComputedStyle(this.root).getPropertyValue("--lw-gap")) || 8;
        var columns;

        if (this.root.dataset.sizing === "scroll") {
            columns = Math.max(1, Math.min(count, Math.round(width / (this.minTileHeight * aspect))));
            var rows = Math.ceil(count / columns);
            var rowHeight = Math.max(this.minTileHeight, (height - gap * (rows - 1)) / rows);
            this.root.style.setProperty("--lw-row-height", rowHeight + "px");
        } else {
            columns = fitColumns(count, width, height, gap, aspect);
            this.root.style.removeProperty("--lw-row-height");
        }
        this.root.style.setProperty("--lw-cols", columns);

        // Measure a real tile rather than trusting the arithmetic: borders, scrollbars and
        // sub-pixel rounding all land here.
        var first = this.grid.firstElementChild;
        if (!first) {
            return;
        }
        var box = first.getBoundingClientRect();
        if (box.width < 4 || box.height < 4) {
            return;
        }

        if (lookup(SQUARE_SHAPES, shape, false)) {
            this.root.style.setProperty("--lw-square", Math.min(box.width, box.height) + "px");
        } else {
            this.root.style.removeProperty("--lw-square");
        }

        this.centreLastRow(count, columns);
        this.resizeText(box, shape);
        this.updateScrolling();
    };

    /* A trailing half-empty row reads as a mistake when it is jammed against the left edge. */
    Wall.prototype.centreLastRow = function (count, columns) {
        var children = this.grid.children;
        for (var i = 0; i < children.length; i++) {
            children[i].style.gridColumnStart = "";
        }
        var remainder = count % columns;
        if (remainder === 0 || columns === 1) {
            return;
        }
        var firstOfLastRow = count - remainder;
        var offset = Math.floor((columns - remainder) / 2) + 1;
        if (children[firstOfLastRow]) {
            children[firstOfLastRow].style.gridColumnStart = String(offset);
        }
    };

    Wall.prototype.resizeText = function (box, shape) {
        var inset = lookup(SHAPE_INSET, shape, 0.1);
        var tileWidth = box.width;
        var tileHeight = box.height;
        if (lookup(SQUARE_SHAPES, shape, false)) {
            tileWidth = Math.min(box.width, box.height);
            tileHeight = tileWidth;
        }
        var availableWidth = tileWidth * (1 - inset * 2);
        var availableHeight = tileHeight * (1 - inset * 2);

        var font = "800 100px " + window.getComputedStyle(this.root).fontFamily;
        var cache = new Map();
        var children = this.grid.children;

        for (var i = 0; i < children.length; i++) {
            var element = children[i];
            var label = element.dataset.label || "";
            var size = cache.get(label);
            if (size === undefined) {
                size = fitFontSize(label, availableWidth, availableHeight, font);
                cache.set(label, size);
            }
            element.style.setProperty("--lw-font-size", size + "px");
        }
    };

    /* Scroll mode: glide the whole grid up and back rather than paginating it. */
    Wall.prototype.updateScrolling = function () {
        if (this.scrollAnimation) {
            this.scrollAnimation.cancel();
            this.scrollAnimation = null;
        }
        this.scroller.style.overflowY = "";
        if (this.root.dataset.sizing !== "scroll" || !this.grid.animate) {
            return;
        }
        if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
            this.scroller.style.overflowY = "auto";
            return;
        }
        var overflow = this.grid.scrollHeight - this.scroller.clientHeight;
        if (overflow <= 4) {
            return;
        }
        var pixelsPerSecond = 24;
        this.scrollAnimation = this.grid.animate(
            [{ transform: "translateY(0)" }, { transform: "translateY(" + -overflow + "px)" }],
            {
                duration: Math.max(8000, (overflow / pixelsPerSecond) * 1000),
                iterations: Infinity,
                direction: "alternate",
                easing: "ease-in-out",
            }
        );
    };

    /* ------------------------------------------------------------------ pure helpers */

    /**
     * Chooses the column count that makes tiles as large as possible while keeping all of them on
     * screen. Every candidate is scored by the area of the largest tile that respects the shape's
     * preferred proportions, which is why a wall of circles lays out differently from a wall of
     * wide rectangles at the same job count.
     */
    function fitColumns(count, width, height, gap, aspect) {
        var best = 0;
        var bestScore = -1;
        for (var columns = 1; columns <= count; columns++) {
            var rows = Math.ceil(count / columns);
            var tileWidth = (width - gap * (columns - 1)) / columns;
            var tileHeight = (height - gap * (rows - 1)) / rows;
            if (tileWidth < 8 || tileHeight < 8) {
                continue;
            }
            var effectiveWidth = tileWidth;
            var effectiveHeight = tileHeight;
            if (effectiveWidth / effectiveHeight > aspect) {
                effectiveWidth = effectiveHeight * aspect;
            } else {
                effectiveHeight = effectiveWidth / aspect;
            }
            var score = effectiveWidth * effectiveHeight;
            if (score > bestScore) {
                bestScore = score;
                best = columns;
            }
        }
        // Nothing fit at all (a very small window): fall back to something square-ish and let the
        // tiles be tiny rather than dropping jobs off the wall.
        return best || Math.max(1, Math.ceil(Math.sqrt((count * width) / Math.max(1, height))));
    }

    /**
     * Largest font size at which the label still fits, found by binary search over real glyph
     * measurements. Job names break at spaces, hyphens, underscores and slashes, which is where
     * Jenkins job names actually want to break.
     */
    function fitFontSize(label, width, height, font) {
        if (!label || width <= 0 || height <= 0) {
            return 12;
        }
        var pieces = splitLabel(label).map(function (piece) {
            return measure(piece, font);
        });
        var lineHeight = 1.08;
        var low = 6;
        var high = Math.max(6, Math.floor(height));

        function fits(size) {
            var scale = size / 100;
            var lines = 1;
            var used = 0;
            for (var i = 0; i < pieces.length; i++) {
                var pieceWidth = pieces[i] * scale;
                if (pieceWidth > width) {
                    return false; // an unbreakable run is wider than the tile
                }
                if (used > 0 && used + pieceWidth > width) {
                    lines++;
                    used = pieceWidth;
                } else {
                    used += pieceWidth;
                }
            }
            return lines * size * lineHeight <= height;
        }

        if (!fits(low)) {
            return low; // nothing fits; the overflow is hidden and the tile still shows its colour
        }
        while (low < high) {
            var middle = Math.ceil((low + high) / 2);
            if (fits(middle)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** Splits a job name into the chunks a line break may fall between, separators included. */
    function splitLabel(label) {
        var pieces = [];
        var current = "";
        for (var i = 0; i < label.length; i++) {
            var character = label.charAt(i);
            current += character;
            if (isSeparator(character) && !isSeparator(label.charAt(i + 1))) {
                pieces.push(current);
                current = "";
            }
        }
        if (current) {
            pieces.push(current);
        }
        return pieces.length ? pieces : [label];
    }

    function isSeparator(character) {
        return character === " " || character === "-" || character === "_" || character === "/";
    }

    /** Width of a string at 100px in the given font, cached because labels repeat across polls. */
    function measure(text, font) {
        var key = font + "\n" + text;
        var cached = glyphWidths[key];
        if (cached === undefined) {
            cached = text.length * 100 * ESTIMATED_GLYPH_RATIO;
            if (measureContext) {
                try {
                    measureContext.font = font;
                    var measured = measureContext.measureText(text).width;
                    if (measured > 0) {
                        cached = measured;
                    }
                } catch (error) {
                    // Keep the estimate.
                }
            }
            glyphWidths[key] = cached;
        }
        return cached;
    }

    /** Writes a label into an element with break opportunities, never via innerHTML. */
    function setLabel(element, label) {
        element.textContent = "";
        var pieces = splitLabel(label);
        for (var i = 0; i < pieces.length; i++) {
            element.appendChild(document.createTextNode(pieces[i]));
            if (i < pieces.length - 1) {
                element.appendChild(document.createElement("wbr"));
            }
        }
    }

    function setFlag(element, name, value) {
        if (value) {
            element.dataset[name] = "true";
        } else {
            delete element.dataset[name];
        }
    }

    /** Black or white, whichever has the better contrast ratio against the fill. */
    function inkFor(color) {
        var rgb = toRgb(color);
        if (!rgb) {
            return "#ffffff";
        }
        var luminance = relativeLuminance(rgb);
        var againstBlack = (luminance + 0.05) / 0.05;
        var againstWhite = 1.05 / (luminance + 0.05);
        return againstBlack >= againstWhite ? "#07090c" : "#ffffff";
    }

    /**
     * Resolves a CSS colour to r/g/b. Hex is parsed directly, since every built-in palette and
     * every validated custom colour is hex; anything else goes through the canvas, which normalises
     * keywords and rgb() for us when it is available.
     */
    function toRgb(color) {
        if (!color) {
            return null;
        }
        var direct = parseHex(color);
        if (direct) {
            return direct;
        }
        if (!measureContext) {
            return null;
        }
        var normalised;
        try {
            measureContext.fillStyle = "#000000";
            measureContext.fillStyle = color;
            normalised = measureContext.fillStyle;
        } catch (error) {
            return null;
        }
        if (typeof normalised !== "string") {
            return null; // no usable canvas; fall back to white ink
        }
        var parsed = parseHex(normalised);
        if (parsed) {
            return parsed;
        }
        var match = normalised.match(/rgba?\(([^)]+)\)/);
        if (!match) {
            return null;
        }
        var parts = match[1].split(",");
        return { r: parseFloat(parts[0]), g: parseFloat(parts[1]), b: parseFloat(parts[2]) };
    }

    /** #rgb, #rrggbb and #rrggbbaa; null for anything else. Alpha is ignored, tiles are opaque. */
    function parseHex(color) {
        var match = /^#([0-9a-fA-F]{3,8})$/.exec(String(color).trim());
        if (!match) {
            return null;
        }
        var hex = match[1];
        if (hex.length === 3 || hex.length === 4) {
            hex = hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        } else if (hex.length !== 6 && hex.length !== 8) {
            return null;
        }
        return {
            r: parseInt(hex.slice(0, 2), 16),
            g: parseInt(hex.slice(2, 4), 16),
            b: parseInt(hex.slice(4, 6), 16),
        };
    }

    function relativeLuminance(rgb) {
        var channels = [rgb.r, rgb.g, rgb.b].map(function (value) {
            var channel = value / 255;
            return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }
})();
