import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { Terminal } from "@/components/terminal/Terminal";
import "./styles.css";

const root = document.getElementById("root");
if (!root) throw new Error("Missing #root");

createRoot(root).render(
  <StrictMode>
    <Terminal />
  </StrictMode>,
);
