import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

/**
 * shadcn/ui Badge primitive mapped onto the site's own badge classes,
 * following the same pattern as `button.tsx`.
 */
const badgeVariants = cva("badge", {
  variants: {
    variant: {
      default: "badge-default",
      success: "badge-success",
      warning: "badge-warning",
      danger: "badge-danger",
      muted: "badge-muted",
    },
  },
  defaultVariants: {
    variant: "default",
  },
});

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

const Badge = React.forwardRef<HTMLSpanElement, BadgeProps>(
  ({ className, variant, ...props }, ref) => (
    <span ref={ref} className={cn(badgeVariants({ variant }), className)} {...props} />
  ),
);
Badge.displayName = "Badge";

export { Badge };
