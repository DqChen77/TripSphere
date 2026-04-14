"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import Link from "next/link";
import { useFrontendTool } from "@copilotkit/react-core";
import { useAgent } from "@copilotkit/react-core/v2";
import { CopilotSidebar } from "@copilotkit/react-core/v2";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { MapPlaceholder } from "@/components/itinerary/map-placeholder";
import { ItineraryViewer } from "@/components/itinerary/itinerary-viewer";
import {
  getItinerary,
  updateSavedItinerary,
  type Itinerary,
} from "@/actions/itinerary";

type SyncStatus = "saved" | "saving" | "unsaved" | "error";

const SYNC_STATUS_LABEL: Record<SyncStatus, string> = {
  saved: "✓ 已保存",
  saving: "⟳ 保存中…",
  unsaved: "● 未保存",
  error: "✕ 保存失败",
};

function SyncStatusBadge({ status }: { status: SyncStatus }) {
  const variantMap = {
    saved: "success",
    unsaved: "warning",
    error: "destructive",
    saving: "outline",
  } as const;

  return (
    <Badge
      variant={variantMap[status]}
      className={cn("rounded-full", status === "saving" && "animate-pulse")}
    >
      {SYNC_STATUS_LABEL[status]}
    </Badge>
  );
}

function PlannerContent() {
  const searchParams = useSearchParams();
  const queryItineraryId = searchParams.get("id")?.trim() ?? "";
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [activeItineraryId, setActiveItineraryId] = useState<string | null>(null);
  const [itinerary, setItinerary] = useState<Itinerary | null>(null);
  const [markdownContent, setMarkdownContent] = useState("");
  const [syncStatus, setSyncStatus] = useState<SyncStatus>("saved");

  const { agent } = useAgent({ agentId: "itinerary_planner" });

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const snapshotRef = useRef<string | null>(null);
  const selfWriteRef = useRef(false);

  const syncToBackend = useCallback(
    async (it: Itinerary, md: string, id: string) => {
      setSyncStatus("saving");
      try {
        await updateSavedItinerary(id, it, md);
        setSyncStatus("saved");
      } catch (err) {
        console.error("[syncToBackend] save failed:", err);
        setSyncStatus("error");
      }
    },
    [],
  );

  const itineraryRef = useRef(itinerary);
  const markdownRef = useRef(markdownContent);
  const idRef = useRef(activeItineraryId);
  useEffect(() => {
    itineraryRef.current = itinerary;
    markdownRef.current = markdownContent;
    idRef.current = activeItineraryId;
  }, [itinerary, markdownContent, activeItineraryId]);

  useFrontendTool(
    {
      name: "update_itinerary",
      description:
        "Persist the current itinerary to the backend after modifications. " +
        "Call this after any itinerary change (add/remove/update activities, days, etc.).",
      parameters: [],
      handler: async () => {
        const id = idRef.current;
        const it = itineraryRef.current;
        const md = markdownRef.current;
        if (!id || !it) return "No itinerary to save.";
        setSyncStatus("saving");
        try {
          await updateSavedItinerary(id, it, md);
          setSyncStatus("saved");
          return "Itinerary saved successfully.";
        } catch (err) {
          console.error("[update_itinerary] save failed:", err);
          setSyncStatus("error");
          return `Save failed: ${err instanceof Error ? err.message : String(err)}`;
        }
      },
    },
    [],
  );

  useEffect(() => {
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      if (debounceTimer.current) {
        clearTimeout(debounceTimer.current);
        debounceTimer.current = null;
      }

      snapshotRef.current = null;
      selfWriteRef.current = false;
      setLoaded(false);
      setLoadError(null);
      setSyncStatus("saved");
      setItinerary(null);
      setMarkdownContent("");
      setActiveItineraryId(null);

      if (!queryItineraryId) {
        setLoadError("缺少行程 ID，请返回“我的行程”重新进入。");
        setLoaded(true);
        return;
      }

      try {
        const data = await getItinerary(queryItineraryId);
        if (cancelled) return;

        const itineraryId = data.itinerary?.id?.trim();
        if (!itineraryId) {
          throw new Error("行程数据缺少 id");
        }

        const snap = JSON.stringify(data.itinerary);
        snapshotRef.current = snap;
        setItinerary(data.itinerary);
        setMarkdownContent(data.markdown_content);
        setActiveItineraryId(itineraryId);

        selfWriteRef.current = true;
        agent.setState({
          itinerary: data.itinerary,
          markdown_content: data.markdown_content,
        });
      } catch (err) {
        if (cancelled) return;
        setLoadError(err instanceof Error ? err.message : "加载行程失败");
      } finally {
        if (!cancelled) {
          setLoaded(true);
        }
      }
    }

    load();
    return () => {
      cancelled = true;
    };
  }, [agent, queryItineraryId]);

  useEffect(() => {
    console.log("🔥 agent.state changed:", agent.state);
  }, [agent.state]);

  useEffect(() => {
    if (!loaded) return;

    if (selfWriteRef.current) {
      selfWriteRef.current = false;
      return;
    }

    const agentItinerary = (
      agent.state as { itinerary?: Itinerary; markdown_content?: string } | null
    )?.itinerary;
    const agentMarkdown = (agent.state as { markdown_content?: string } | null)
      ?.markdown_content;
    const activeId = idRef.current;
    if (!activeId) return;
    const localItinerary = itineraryRef.current;
    const localMarkdown = markdownRef.current;

    if (!agentItinerary?.id) {
      // Backend sent an empty or schema-default STATE_SNAPSHOT (no real itinerary yet).
      // Restore the local itinerary back into the agent so it retains context.
      if (localItinerary?.id === activeId) {
        selfWriteRef.current = true;
        agent.setState({
          itinerary: localItinerary,
          markdown_content: localMarkdown,
        });
      }
      return;
    }

    // Ignore stale snapshots from old planner sessions.
    if (agentItinerary.id !== activeId) {
      if (localItinerary?.id === activeId) {
        selfWriteRef.current = true;
        agent.setState({
          itinerary: localItinerary,
          markdown_content: localMarkdown,
        });
      }
      return;
    }

    const snap = JSON.stringify(agentItinerary);
    if (snap === snapshotRef.current) return;

    snapshotRef.current = snap;
    setItinerary(agentItinerary);
    if (agentMarkdown !== undefined) setMarkdownContent(agentMarkdown);

    setSyncStatus("unsaved");
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      const latestId = idRef.current;
      if (!latestId || latestId !== agentItinerary.id) return;
      void syncToBackend(agentItinerary, agentMarkdown ?? "", latestId);
    }, 1500);
  }, [agent, agent.state, loaded, syncToBackend]);

  if (!loaded) {
    return (
      <div className="flex h-[calc(100vh-4rem)] items-center justify-center">
        <p className="text-muted-foreground">加载中…</p>
      </div>
    );
  }

  if (!itinerary) {
    return (
      <div className="flex h-[calc(100vh-4rem)] items-center justify-center">
        <div className="text-center">
          <p className="text-foreground text-lg font-medium">
            {loadError ? "行程加载失败" : "暂无行程数据"}
          </p>
          {loadError && (
            <p className="text-muted-foreground mt-2 text-sm">{loadError}</p>
          )}
          <Link
            href="/itinerary"
            className="text-primary mt-2 inline-block text-sm hover:underline"
          >
            返回我的行程
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-[calc(100vh-4rem)] flex-col">
      <div className="border-border bg-background flex shrink-0 items-center justify-between border-b px-4 py-2">
        <div className="text-muted-foreground flex items-center gap-2 text-sm">
          <span className="text-foreground font-medium">
            {itinerary.destination}
          </span>
          <span>·</span>
          <span>
            {itinerary.start_date} ~ {itinerary.end_date}
          </span>
        </div>
        <div className="flex items-center gap-3">
          <SyncStatusBadge status={syncStatus} />
          <Link
            href="/itinerary"
            className="text-primary text-xs hover:underline"
          >
            我的行程
          </Link>
        </div>
      </div>

      <div className="flex flex-1 overflow-hidden">
        <div className="hidden min-w-0 flex-1 overflow-hidden border-r lg:block">
          <MapPlaceholder />
        </div>

        <div className="w-[40rem] shrink-0 overflow-hidden">
          <ItineraryViewer
            itinerary={itinerary}
            markdownContent={markdownContent}
          />
        </div>

        <CopilotSidebar
          agentId="itinerary_planner"
          defaultOpen={true}
          width="30rem"
          labels={{
            modalHeaderTitle: `AI行程助手 · ${itinerary.destination}`,
            chatInputPlaceholder: "告诉我你想如何修改行程…",
          }}
        />
      </div>
    </div>
  );
}

export default function ItineraryPlannerPage() {
  return (
    <Suspense>
      <PlannerContent />
    </Suspense>
  );
}
